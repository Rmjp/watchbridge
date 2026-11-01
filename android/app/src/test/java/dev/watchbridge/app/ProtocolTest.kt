package dev.watchbridge.app

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtocolTest {
    private fun vector()=JSONObject(File("../../test-vectors/crypto-v1.json").readText())
    @Test fun crossPlatformPairingAndAuthenticatedCiphertext() {
        val v=vector()
        val (key,sas)=BridgeCrypto.deriveMaterial(v.getString("androidPrivatePKCS8").unb64(),v.getString("androidPublic"),v.getString("watchPublic"))
        assertEquals(v.getString("key"),key.b64());assertEquals(v.getString("securityCode"),sas)
        val e=v.getJSONObject("envelope")
        val direction=BridgeCrypto.hkdf(key,byteArrayOf(),"watchbridge.v1.sender:${e.getString("sender")}")
        assertEquals(v.getString("directionalKey"),direction.b64());assertEquals(v.getString("aad"),String(BridgeCrypto.aad(e)))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(direction,"AES"),GCMParameterSpec(128,e.getString("nonce").unb64()));cipher.updateAAD(BridgeCrypto.aad(e))
        assertEquals(e.getString("ciphertext"),cipher.doFinal("{\"text\":\"hello\"}".toByteArray()).b64())
        val bad=Cipher.getInstance("AES/GCM/NoPadding");bad.init(Cipher.DECRYPT_MODE,SecretKeySpec(direction,"AES"),GCMParameterSpec(128,e.getString("nonce").unb64()));bad.updateAAD("tampered".toByteArray())
        assertThrows(javax.crypto.AEADBadTagException::class.java){bad.doFinal(e.getString("ciphertext").unb64())}
    }
}
