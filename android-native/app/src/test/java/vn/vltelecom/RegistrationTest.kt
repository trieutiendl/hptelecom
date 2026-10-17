package vn.vltelecom

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class RegistrationTest {
 private val expected="a".repeat(32)
 private fun payload(email:String="name@example.test")=Registration.payload(" Nguyễn Văn A "," NEW.PERSON "," secret-pass-123 "," Đội đề nghị ","+84 912 345 678",email)
 private fun status(id:String=expected)=JSONObject().put("server_id",id).put("needs_setup",false)
  .put("capabilities",JSONArray().put(Registration.capability))

 @Test fun payloadKeepsPasswordAndNormalizesOnlyAccountDetails(){
  val body=payload()
  assertEquals(setOf("display_name","username","password","team","phone","email"),body.keys().asSequence().toSet())
  assertEquals("new.person",body.getString("username"))
  assertEquals("Nguyễn Văn A",body.getString("display_name"))
  assertEquals(" secret-pass-123 ",body.getString("password"))
  assertFalse(body.has("enabled"));assertFalse(body.has("role"));assertFalse(body.has("modules"))
 }

 @Test fun invalidContactsAccountAndPrivilegesCannotBeSubmitted(){
  for(email in listOf("a@b..c","a@b", "a b@example.test","a@.example.test")){
   assertThrows(IllegalArgumentException::class.java){payload(email)}
  }
  for(values in listOf(listOf("", "valid", "Password-123", "Team", "123456", "a@b.test"),
   listOf("Name", "a b", "Password-123", "Team", "123456", "a@b.test"),
   listOf("Name", "valid", "short", "Team", "123456", "a@b.test"),
   listOf("Name", "valid", "x".repeat(201), "Team", "123456", "a@b.test"),
   listOf("Name", "valid", "Password-123", "", "123456", "a@b.test"),
   listOf("Name", "valid", "Password-123", "Team", "abc123456", "a@b.test"),
   listOf("Name\u0000bad", "valid", "Password-123", "Team", "123456", "a@b.test"))){
   assertThrows(IllegalArgumentException::class.java){Registration.payload(values[0],values[1],values[2],values[3],values[4],values[5])}
  }
 }

 @Test fun identityOrCapabilityMismatchNeverSendsCredentials()=runBlocking{
  val replies=listOf(status("b".repeat(32)),status().put("capabilities",JSONArray().put(Registration.capability+"-other")),
   status().put("needs_setup",true),status().apply{remove("needs_setup")})
  for(reply in replies){
   val paths=mutableListOf<String>()
   try{Registration.submitVerified("https://example.test",expected,payload()){_,path,method,body->
    paths+=path;assertEquals("GET",method);assertNull(body);reply
   };fail("Should reject before register")}
   catch(_:IllegalStateException){}
   assertEquals(listOf("status"),paths)
  }
 }

 @Test fun pendingResponseDoesNotAuthenticateOrReplaceExistingSession()=runBlocking{
  val oldUrl=ServerSession.url;val oldToken=ServerSession.token;val oldUser=ServerSession.user
  try{
   ServerSession.url="https://original.test";ServerSession.token="existing-session";ServerSession.user=JSONObject().put("id","old-user")
   val calls=mutableListOf<String>()
   val message=Registration.submitVerified("https://example.test",expected,payload()){address,path,method,body->
    assertEquals("https://example.test",address);calls+=path
    if(path=="status"){assertEquals("GET",method);assertNull(body);status()}
    else{assertEquals("POST",method);assertEquals("new.person",body!!.getString("username"));JSONObject().put("ok",true).put("status","pending")}
   }
   assertEquals(listOf("status","register"),calls)
   assertTrue(message.contains("quản trị"))
   assertEquals("existing-session",ServerSession.token)
   assertEquals("https://original.test",ServerSession.url)
   assertEquals("old-user",ServerSession.user!!.getString("id"))
  }finally{ServerSession.url=oldUrl;ServerSession.token=oldToken;ServerSession.user=oldUser}
 }

 @Test fun unexpectedActiveResponseIsNotTreatedAsApproval()=runBlocking{
  for(reply in listOf(JSONObject().put("ok",true).put("status","active"),
    JSONObject().put("ok",true).put("status","pending").put("token","unwanted"))){
   try{Registration.submitVerified("https://example.test",expected,payload()){_,path,_,_->if(path=="status")status()else reply};fail("Should require pending without token")}
   catch(_:IllegalStateException){}
  }
 }

 @Test fun knownServerPinIsRequiredBeforeDiscoveryOrSubmission(){
  assertEquals(ServerDiscovery.defaultServerId,Registration.expectedId(ServerDiscovery.defaultUrl,"b".repeat(32),null))
  assertEquals(expected,Registration.expectedId("https://company.example.test",expected,null))
  assertThrows(IllegalStateException::class.java){Registration.expectedId("https://unknown.example.test",null,null)}
 }

 private class FakeConnection(val code:Int,val content:String):HttpURLConnection(URL("https://example.test/api/register")){
  val written=ByteArrayOutputStream();var closed=false
  override fun connect(){}
  override fun disconnect(){closed=true}
  override fun usingProxy()=false
  override fun getResponseCode()=code
  override fun getInputStream()=ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
  override fun getErrorStream()=ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
  override fun getOutputStream()=written
 }

 @Test fun transportSendsNoAuthDoesNotFollowRedirectAndDoesNotClearSessionOn401(){
  val oldToken=ServerSession.token
  try{
   ServerSession.token="keep-offline-session"
   val connection=FakeConnection(401,"{\"error\":\"Request denied\"}")
   assertThrows(IllegalStateException::class.java){Registration.exchange("https://example.test","register","POST",payload()){connection}}
   assertNull(connection.getRequestProperty("Authorization"))
   assertFalse(connection.instanceFollowRedirects)
   assertEquals("POST",connection.requestMethod)
   assertEquals("new.person",JSONObject(connection.written.toString("UTF-8")).getString("username"))
   assertEquals("keep-offline-session",ServerSession.token)
   assertTrue(connection.closed)
  }finally{ServerSession.token=oldToken}
 }

 @Test fun transportBoundsResponseAndDisconnectsOnFailure(){
  val connection=FakeConnection(200,"x".repeat(32769))
  assertThrows(IllegalStateException::class.java){Registration.exchange("https://example.test","status","GET",null){connection}}
  assertTrue(connection.closed)
  assertEquals(0,connection.written.size())
 }
}
