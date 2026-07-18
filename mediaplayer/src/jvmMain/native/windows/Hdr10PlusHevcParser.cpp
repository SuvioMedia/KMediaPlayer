#include "Hdr10PlusHevcParser.h"

#include <algorithm>
#include <limits>

namespace Hdr10PlusHevc {
namespace {

constexpr uint8_t kPrefixSeiNalType = 39;
constexpr uint8_t kSuffixSeiNalType = 40;
constexpr size_t kNalHeaderSize = 2;
constexpr uint32_t kUserDataRegisteredItuTT35PayloadType = 4;

bool IsHdr10PlusApplication4Version1(const uint8_t* data, size_t size) {
    return size >= 7 &&
        data[0] == 0xb5 &&
        data[1] == 0x00 && data[2] == 0x3c &&
        data[3] == 0x00 && data[4] == 0x01 &&
        data[5] == 0x04 && data[6] == 0x01;
}

bool IsStartCode(const uint8_t* data, size_t size, size_t offset, size_t& length) {
    if (offset + 3 <= size && data[offset] == 0 && data[offset + 1] == 0 && data[offset + 2] == 1) {
        length = 3;
        return true;
    }
    if (offset + 4 <= size && data[offset] == 0 && data[offset + 1] == 0 &&
        data[offset + 2] == 0 && data[offset + 3] == 1) {
        length = 4;
        return true;
    }
    return false;
}

bool ParseSeiRbsp(const uint8_t* encoded, size_t encodedSize, std::vector<uint8_t>& payload) {
    std::vector<uint8_t> rbsp;
    rbsp.reserve(encodedSize);
    unsigned zeroCount = 0;
    for (size_t index = 0; index < encodedSize; ++index) {
        const uint8_t value = encoded[index];
        if (zeroCount >= 2 && value == 0x03) {
            zeroCount = 0;
            continue;
        }
        rbsp.push_back(value);
        zeroCount = value == 0 ? zeroCount + 1 : 0;
    }

    size_t offset = 0;
    while (offset < rbsp.size()) {
        if (rbsp[offset] == 0x80) break;
        uint32_t payloadType = 0;
        while (offset < rbsp.size() && rbsp[offset] == 0xff) {
            if (payloadType > std::numeric_limits<uint32_t>::max() - 255u) return false;
            payloadType += 255u;
            ++offset;
        }
        if (offset >= rbsp.size()) return false;
        payloadType += rbsp[offset++];

        size_t payloadSize = 0;
        while (offset < rbsp.size() && rbsp[offset] == 0xff) {
            if (payloadSize > std::numeric_limits<size_t>::max() - 255u) return false;
            payloadSize += 255u;
            ++offset;
        }
        if (offset >= rbsp.size()) return false;
        if (payloadSize > std::numeric_limits<size_t>::max() - rbsp[offset]) return false;
        payloadSize += rbsp[offset++];
        if (payloadSize > rbsp.size() - offset) return false;

        if (payloadType == kUserDataRegisteredItuTT35PayloadType &&
            payloadSize <= kMaximumPayloadSize &&
            IsHdr10PlusApplication4Version1(rbsp.data() + offset, payloadSize)) {
            payload.assign(rbsp.begin() + static_cast<ptrdiff_t>(offset),
                           rbsp.begin() + static_cast<ptrdiff_t>(offset + payloadSize));
            return true;
        }
        offset += payloadSize;
    }
    return false;
}

bool ParseNal(const uint8_t* nal, size_t size, std::vector<uint8_t>& payload) {
    if (!nal || size <= kNalHeaderSize) return false;
    const uint8_t nalType = (nal[0] >> 1u) & 0x3fu;
    if (nalType != kPrefixSeiNalType && nalType != kSuffixSeiNalType) return false;
    return ParseSeiRbsp(nal + kNalHeaderSize, size - kNalHeaderSize, payload);
}

bool ParseAnnexB(const uint8_t* sample, size_t size, std::vector<uint8_t>& payload) {
    size_t cursor = 0;
    bool foundStartCode = false;
    while (cursor < size) {
        size_t startCodeLength = 0;
        while (cursor < size && !IsStartCode(sample, size, cursor, startCodeLength)) ++cursor;
        if (cursor >= size) break;
        foundStartCode = true;
        const size_t nalStart = cursor + startCodeLength;
        size_t nalEnd = nalStart;
        size_t nextStartCodeLength = 0;
        while (nalEnd < size && !IsStartCode(sample, size, nalEnd, nextStartCodeLength)) ++nalEnd;
        if (ParseNal(sample + nalStart, nalEnd - nalStart, payload)) return true;
        cursor = nalEnd;
    }
    return foundStartCode && !payload.empty();
}

bool ParseLengthPrefixed(
    const uint8_t* sample,
    size_t size,
    uint8_t nalLengthSize,
    std::vector<uint8_t>& payload) {
    if (nalLengthSize < 1 || nalLengthSize > 4) return false;
    size_t offset = 0;
    while (offset + nalLengthSize <= size) {
        uint32_t nalSize = 0;
        for (uint8_t index = 0; index < nalLengthSize; ++index) {
            nalSize = (nalSize << 8u) | sample[offset + index];
        }
        offset += nalLengthSize;
        if (nalSize == 0 || nalSize > size - offset) return false;
        if (ParseNal(sample + offset, nalSize, payload)) return true;
        offset += nalSize;
    }
    return false;
}

} // namespace

bool ExtractPayload(
    const uint8_t* sample,
    size_t sampleSize,
    uint8_t nalLengthSize,
    std::vector<uint8_t>& payload) {
    payload.clear();
    if (!sample || sampleSize == 0) return false;
    size_t ignored = 0;
    if (IsStartCode(sample, sampleSize, 0, ignored)) return ParseAnnexB(sample, sampleSize, payload);
    return ParseLengthPrefixed(sample, sampleSize, nalLengthSize, payload);
}

} // namespace Hdr10PlusHevc
