if(NOT DEFINED INPUT OR NOT DEFINED OUTPUT OR NOT DEFINED SYMBOL)
    message(FATAL_ERROR "INPUT, OUTPUT and SYMBOL are required")
endif()

file(READ "${INPUT}" CONTENT HEX)
string(LENGTH "${CONTENT}" HEX_LENGTH)
math(EXPR BYTE_COUNT "${HEX_LENGTH} / 2")
set(BODY "")
set(INDEX 0)
while(INDEX LESS HEX_LENGTH)
    string(SUBSTRING "${CONTENT}" ${INDEX} 2 BYTE)
    string(APPEND BODY "0x${BYTE},")
    math(EXPR INDEX "${INDEX} + 2")
endwhile()

file(WRITE "${OUTPUT}"
    "#ifndef ${SYMBOL}_H\n"
    "#define ${SYMBOL}_H\n"
    "#include <stddef.h>\n"
    "#include <stdint.h>\n"
    "static const uint8_t ${SYMBOL}[] = {${BODY}};\n"
    "static const size_t ${SYMBOL}_size = ${BYTE_COUNT};\n"
    "#endif\n"
)
