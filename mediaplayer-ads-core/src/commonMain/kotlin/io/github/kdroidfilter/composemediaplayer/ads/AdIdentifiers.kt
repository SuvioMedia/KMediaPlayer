package io.github.kdroidfilter.composemediaplayer.ads

private const val MAX_OPAQUE_IDENTIFIER_LENGTH = 512

private fun validateOpaqueIdentifier(
    type: String,
    value: String,
) {
    require(value.isNotBlank()) { "$type must not be blank." }
    require(value.length <= MAX_OPAQUE_IDENTIFIER_LENGTH) {
        "$type must not exceed $MAX_OPAQUE_IDENTIFIER_LENGTH characters."
    }
}

public class AdSessionId(
    public val value: String,
) {
    init {
        validateOpaqueIdentifier("Ad session id", value)
    }

    override fun equals(other: Any?): Boolean = other is AdSessionId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "AdSessionId([redacted])"
}

public class AdBreakId(
    public val value: String,
) {
    init {
        validateOpaqueIdentifier("Ad break id", value)
    }

    override fun equals(other: Any?): Boolean = other is AdBreakId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "AdBreakId([redacted])"
}

public class AdId(
    public val value: String,
) {
    init {
        validateOpaqueIdentifier("Ad id", value)
    }

    override fun equals(other: Any?): Boolean = other is AdId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "AdId([redacted])"
}

public class AdResourceRef(
    public val value: String,
) {
    init {
        validateOpaqueIdentifier("Ad resource reference", value)
    }

    override fun equals(other: Any?): Boolean = other is AdResourceRef && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "AdResourceRef([redacted])"
}

public class AdActionRef(
    public val value: String,
) {
    init {
        validateOpaqueIdentifier("Ad action reference", value)
    }

    override fun equals(other: Any?): Boolean = other is AdActionRef && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "AdActionRef([redacted])"
}

public class AdVerificationParametersRef(
    public val value: String,
) {
    init {
        validateOpaqueIdentifier("Ad verification parameters reference", value)
    }

    override fun equals(other: Any?): Boolean = other is AdVerificationParametersRef && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "AdVerificationParametersRef([redacted])"
}
