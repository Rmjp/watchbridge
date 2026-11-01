package dev.watchbridge.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun ByteArray.b64(): String = java.util.Base64.getEncoder().encodeToString(this)
internal fun String.unb64(): ByteArray = java.util.Base64.getDecoder().decode(this)

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val key: SecretKey
        get() {
            if (!store.containsAlias("WatchBridgeVault")) {
                KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder("WatchBridgeVault", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
                    generateKey()
                }
            }
            return store.getKey("WatchBridgeVault", null) as SecretKey
        }
    @Synchronized fun get(name: String): String? = prefs.getString(name, null)?.let(::open)
    @Synchronized fun put(name: String, value: String?) { check(prefs.edit().apply { if (value == null) remove(name) else putString(name, seal(value)) }.commit()) }
    fun seal(value: String): String { val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);return (c.iv+c.doFinal(value.toByteArray())).b64() }
    fun open(value: String): String { val b=value.unb64();val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,b.copyOfRange(0,12)));return String(c.doFinal(b.copyOfRange(12,b.size))) }
    fun clear() { check(prefs.edit().clear().commit()) }
}

object BridgeCrypto {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    fun hkdf(input: ByteArray, salt: ByteArray, info: String): ByteArray {
        fun h(k: ByteArray, d: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k,"HmacSHA256"));doFinal(d) }
        return h(h(if(salt.isEmpty()) ByteArray(32) else salt,input),info.toByteArray()+byteArrayOf(1))
    }
    fun newIdentity(v: Vault) {
        val pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        fun fixed(n: BigInteger)=n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32-it.size)+it }
        val p=pair.public as ECPublicKey
        v.put("private",pair.private.encoded.b64());v.put("public",(byteArrayOf(4)+fixed(p.w.affineX)+fixed(p.w.affineY)).b64());v.put("id",java.util.UUID.randomUUID().toString())
    }
    fun derive(v: Vault, peer: String): Pair<ByteArray,String> {
        return deriveMaterial(v.get("private")!!.unb64(),v.get("public")!!,peer)
    }
    fun deriveMaterial(privateBytes:ByteArray,ownPublic:String,peer:String):Pair<ByteArray,String> {
        val raw=peer.unb64();require(raw.size==65 && raw[0]==4.toByte())
        val params=AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val pub=KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1,raw.copyOfRange(1,33)),BigInteger(1,raw.copyOfRange(33,65))),params))
        val priv=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
        val shared=KeyAgreement.getInstance("ECDH").run { init(priv);doPhase(pub,true);generateSecret() }
        val salt=sha("$ownPublic|$peer".toByteArray())
        val key=hkdf(shared,salt,"watchbridge.v1")
        val digest=sha(key+salt);var n=0L;for(i in 0..3)n=(n shl 8) or (digest[i].toLong() and 255)
        return key to (n%1000000).toString().padStart(6,'0')
    }
    internal fun aad(e: JSONObject)="watchbridge.v1|${e.getString("id")}|${e.getString("sender")}|${e.getString("recipient")}|${e.getString("type")}|${e.getLong("created")}|${e.getLong("expires")}".toByteArray()
    fun encrypt(v: Vault, type: String, data: JSONObject): JSONObject {
        val now=System.currentTimeMillis()
        val e=JSONObject().put("v",1).put("id",java.util.UUID.randomUUID().toString()).put("sender",v.get("id")).put("recipient",v.get("peerId")).put("type",type).put("created",now).put("expires",now+if(type=="health")604800000 else 86400000)
        val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key=hkdf(v.get("key")!!.unb64(),byteArrayOf(),"watchbridge.v1.sender:${e.getString("sender")}")
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad(e))
        return e.put("nonce",nonce.b64()).put("ciphertext",c.doFinal(data.toString().toByteArray()).b64())
    }
    fun decrypt(v: Vault,e: JSONObject): JSONObject {
        require(e.getInt("v")==1 && e.getString("sender")==v.get("peerId") && e.getString("recipient")==v.get("id"))
        require(e.getLong("expires")>System.currentTimeMillis() && e.getLong("created")<=System.currentTimeMillis()+300000)
        val nonce=e.getString("nonce").unb64();require(nonce.size==12)
        val key=hkdf(v.get("key")!!.unb64(),byteArrayOf(),"watchbridge.v1.sender:${e.getString("sender")}")
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));c.updateAAD(aad(e))
        return JSONObject(String(c.doFinal(e.getString("ciphertext").unb64())))
    }
}
