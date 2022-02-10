/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.apm.agent.premain;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.FileSystems;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONObject;

public class SFAgentUtil {
  private static byte[] key;
  
  public SFConfigInfo parseSFAgentYamlFile() {
    FileReader fr = null;
    try {
      File file = new File(setSFtraceConfig());
      fr = new FileReader(file);
      BufferedReader br = new BufferedReader(fr);
      SFConfigInfo cfgInfo = new SFConfigInfo();
      SFTagInfo tagInfo = new SFTagInfo();
      String line;
      while ((line = br.readLine()) != null) {
        String[] keyValue = line.split(":");
        if (keyValue.length == 2) {
          String key = keyValue[0].trim();
          String value = keyValue[1].trim();
          if (key.equals("key")) {
            cfgInfo.setKey(value);
            continue;
          } 
          if (key.equals("appName")) {
            tagInfo.setAppName(value);
            continue;
          } 
          if (key.equals("projectName"))
            tagInfo.setProjectName(value); 
        } 
      } 
      cfgInfo.setTags(tagInfo);
      String serviceName = System.getProperty("sftrace.service_name");
      if (serviceName != null && !serviceName.isEmpty())
        cfgInfo.setServiceName(serviceName); 
      return cfgInfo;
    } catch (IOException e) {
      System.out.println("Exception in SFparseSFAgentYamlFile: " + e.getMessage());
      e.printStackTrace();
      return null;
    } catch (Exception e) {
      System.out.println("Exception in SFparseSFAgentYamlFile: " + e.getMessage());
      e.printStackTrace();
      return null;
    } finally {
      try {
        fr.close();
      } catch (IOException iOException) {
      
      } catch (Exception exception) {}
    } 
  }
  
  public String setSFtraceConfig() {
	  
	  String sfConfFileName = "config.yaml";
	  String s = FileSystems.getDefault().getSeparator();
	  String confProp = System.getProperty("sftrace.config");
	  String confEnv = System.getenv("SFTRACE_CONFIG");
	  String sfConf = confEnv != null ? confEnv :
		  					confProp != null ? confProp:
		  						"/opt/sfagent"; //Fallback to Linux path
	  
	  return sfConf+s+sfConfFileName;
			  			
  }
  
  public void setAgentInputParams() {
    try {
      SFConfigInfo cfgInfo = parseSFAgentYamlFile();
      if (cfgInfo == null) {
        cfgInfo = getConfigFromSystemProperty();
        if (cfgInfo == null) {
          System.out.println("Input parameters not found. Agent will not be attahced correctly");
          return;
        } 
      } 
      String decryptedValue = decrypt(cfgInfo.getKey(), "SnappyFlow123456");
      JSONObject jsonbObj = new JSONObject(decryptedValue);
      String URL = jsonbObj.get("trace_server_url").toString();
      System.out.println("SERVER URL: " + URL);
      System.setProperty("elastic.apm.server_urls", URL);
      System.setProperty("elastic.apm.verify_server_cert", "false");
      System.setProperty("elastic.apm.central_config", "false");
      String serviceName = cfgInfo.getServiceName();
      if (serviceName != null && !serviceName.isEmpty())
        System.setProperty("elastic.apm.service_name", serviceName); 
      String profileId = jsonbObj.get("profile_id").toString();
      String globalLabels = System.getProperty("elastic.apm.global_labels");
      if (globalLabels != null && !globalLabels.isEmpty()) {
        globalLabels = globalLabels + ",";
      } else {
        globalLabels = "";
      } 
      String newLabels = "";
      if (cfgInfo.getTags() != null)
        newLabels = "_tag_appName=" + cfgInfo.getTags().getAppName() + ",_tag_projectName=" + cfgInfo.getTags().getProjectName(); 
      if (!newLabels.isEmpty())
        newLabels = newLabels + ","; 
      newLabels = newLabels + "_tag_profileId=" + profileId;
      globalLabels = globalLabels + newLabels;
      System.setProperty("elastic.apm.global_labels", globalLabels);
    } catch (Exception e) {
      System.out.println("Exception in SFsetAgentInputParams: " + e.getMessage());
      e.printStackTrace();
    } 
  }
  
  public SFConfigInfo getConfigFromSystemProperty() {
    try {
      SFConfigInfo cfgInfo = null;
      String profileKey = System.getenv("SFTRACE_PROFILE_KEY");
      String projectName = System.getenv("SFTRACE_PROJECT_NAME");
      String appName = System.getenv("SFTRACE_APP_NAME");
      String serviceName = System.getenv("SFTRACE_SERVICE_NAME");
      if (profileKey != null && !profileKey.isEmpty()) {
        cfgInfo = new SFConfigInfo();
        cfgInfo.setKey(profileKey);
      } 
      if (serviceName != null && !serviceName.isEmpty()) {
        if (cfgInfo == null)
          cfgInfo = new SFConfigInfo(); 
        cfgInfo.setServiceName(serviceName);
      } 
      SFTagInfo tagInfo = new SFTagInfo();
      if (projectName != null && !projectName.isEmpty())
        tagInfo.setProjectName(projectName); 
      if (appName != null && !appName.isEmpty())
        tagInfo.setAppName(appName); 
      if (cfgInfo != null) {
        if (tagInfo != null)
          cfgInfo.setTags(tagInfo); 
        return cfgInfo;
      } 
      return null;
    } catch (Exception e) {
      System.out.println("Exception in SFgetConfigFromSystemProperty: " + e.getMessage());
      e.printStackTrace();
      return null;
    } 
  }
  
  public static String decrypt(String strToDecrypt, String secret) {
    try {
      byte[] decodedArray = Base64.decodeBase64(strToDecrypt.getBytes());
      setKey(secret);
      byte[] ivArray = Arrays.copyOfRange(decodedArray, 0, 16);
      byte[] toDecryptArray = Arrays.copyOfRange(decodedArray, 16, decodedArray.length);
      IvParameterSpec iv = new IvParameterSpec(ivArray);
      SecretKeySpec skeySpec = new SecretKeySpec(secret.getBytes("UTF-8"), "AES");
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
      cipher.init(2, skeySpec, iv);
      return new String(cipher.doFinal(toDecryptArray));
    } catch (Exception e) {
      System.err.println("Exception in decrypt()");
      return null;
    } 
  }
  
  public static void setKey(String myKey) {
    MessageDigest sha = null;
    try {
      key = myKey.getBytes("UTF-8");
      sha = MessageDigest.getInstance("SHA-1");
      key = sha.digest(key);
      key = Arrays.copyOf(key, 16);
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    } 
  }
}
