package vn.vltelecom

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

object RememberLogin {
 private const val alias="hp-telecom-server-login"
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  return (store.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
   init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 fun save(context:Context,value:JSONObject?,slot:String=alias) {
  val prefs=context.getSharedPreferences(slot,Context.MODE_PRIVATE)
  if(value==null){check(prefs.edit().clear().commit());return}
  val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
  val bytes=cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
  check(prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).putString("value",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())
 }
 fun read(context:Context,slot:String=alias):JSONObject? {
  val prefs=context.getSharedPreferences(slot,Context.MODE_PRIVATE)
  val value=prefs.getString("value",null)?:return null
  return try {
   val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv",null),Base64.NO_WRAP)))}
   JSONObject(String(cipher.doFinal(Base64.decode(value,Base64.NO_WRAP)),Charsets.UTF_8))
  }catch(e:Exception){save(context,null,slot);null}
 }
}
