package io.baton.cal.snapshot

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * BATON과 공유하는 다이제스트의 필드 인코딩이다. 스냅샷 지문과 복구 다이제스트가 함께 사용하며 규칙은
 * PRD-0002 `복구 다이제스트` 절을 따른다. `writeUTF` 같은 다른 길이 표기가 섞이지 않도록 계약 형식만 노출한다.
 */
class DigestWriter private constructor(
    private val output: DataOutputStream,
) {
    fun string(value: String) {
        val bytes = value.encodeToByteArray()
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    fun nullableString(value: String?) {
        if (value == null) output.writeInt(NULL_STRING_LENGTH) else string(value)
    }

    fun int(value: Int) {
        output.writeInt(value)
    }

    fun nullableInt(value: Int?) {
        output.writeBoolean(value != null)
        if (value != null) output.writeInt(value)
    }

    companion object {
        private const val NULL_STRING_LENGTH = -1

        /** [write]로 쓴 필드의 SHA-256을 소문자 16진수 64자리로 반환한다. */
        fun sha256(write: DigestWriter.() -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), digest)).use { output ->
                DigestWriter(output).write()
            }
            return digest.digest().toHexString()
        }
    }
}
