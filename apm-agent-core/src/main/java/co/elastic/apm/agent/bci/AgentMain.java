/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2021 Elastic and contributors
 * %%
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
 * #L%
 */

package co.elastic.apm.agent.bci;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import javax.annotation.Nullable;

public class AgentMain {
  public static void premain(String agentArguments, Instrumentation instrumentation) {
    init(agentArguments, instrumentation, true);
  }
  
  public static void agentmain(String agentArguments, Instrumentation instrumentation) {
    init(agentArguments, instrumentation, false);
  }
  
  public static synchronized void init(String agentArguments, Instrumentation instrumentation, boolean premain) {
    if (Boolean.getBoolean("ElasticApm.attached"))
      return; 
    SFsetAgentParameters();
    String javaVersion = System.getProperty("java.version");
    String javaVmName = System.getProperty("java.vm.name");
    String javaVmVersion = System.getProperty("java.vm.version");
    if (!isJavaVersionSupported(javaVersion, javaVmName, javaVmVersion)) {
      System.err.println(String.format("Failed to start agent - JVM version not supported: %s %s %s", new Object[] { javaVersion, javaVmName, javaVmVersion }));
      return;
    } 
    try {
      FileSystems.getDefault();
      File agentJarFile = getAgentJarFile();
      JarFile jarFile = new JarFile(agentJarFile);
      try {
        instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
        jarFile.close();
      } catch (Throwable throwable) {
        try {
          jarFile.close();
        } catch (Throwable throwable1) {
          throwable.addSuppressed(throwable1);
        } 
        throw throwable;
      } 
      Class.forName("co.elastic.apm.agent.bci.ElasticApmAgent", true, null)
        .getMethod("initialize", new Class[] { String.class, Instrumentation.class, File.class, boolean.class }).invoke(null, new Object[] { agentArguments, instrumentation, agentJarFile, Boolean.valueOf(premain) });
      System.setProperty("ElasticApm.attached", Boolean.TRUE.toString());
    } catch (Exception|LinkageError e) {
      System.err.println("Failed to start agent");
      e.printStackTrace();
    } 
  }
  
  private static void SFsetAgentParameters() {
    try {
      SFAgentUtil sfUtil = new SFAgentUtil();
      sfUtil.setAgentInputParams();
    } catch (Exception e) {
      System.out.println("Exception in SFsetAgentParameters: " + e.getMessage());
      e.printStackTrace();
    } 
  }
  
  static boolean isJavaVersionSupported(String version, String vmName, @Nullable String vmVersion) {
    int major;
    if (version.startsWith("1.")) {
      major = Character.digit(version.charAt(2), 10);
    } else {
      String majorAsString = version.split("\\.")[0];
      int indexOfDash = majorAsString.indexOf('-');
      if (indexOfDash > 0)
        majorAsString = majorAsString.substring(0, indexOfDash); 
      major = Integer.parseInt(majorAsString);
    } 
    boolean isHotSpot = (vmName.contains("HotSpot(TM)") || vmName.contains("OpenJDK"));
    boolean isIbmJ9 = vmName.contains("IBM J9");
    if (major < 7)
      return false; 
    if (isHotSpot)
      return isHotSpotVersionSupported(version, major); 
    if (isIbmJ9)
      return isIbmJ9VersionSupported(vmVersion, major); 
    return true;
  }
  
  private static boolean isHotSpotVersionSupported(String version, int major) {
    switch (major) {
      case 7:
        return isUpdateVersionAtLeast(version, 60);
      case 8:
        return isUpdateVersionAtLeast(version, 40);
    } 
    return true;
  }
  
  private static boolean isIbmJ9VersionSupported(@Nullable String vmVersion, int major) {
    switch (major) {
      case 7:
        return false;
      case 8:
        return !"2.8".equals(vmVersion);
    } 
    return true;
  }
  
  private static boolean isUpdateVersionAtLeast(String version, int minimumUpdateVersion) {
    String updateVersion;
    int updateIndex = version.lastIndexOf("_");
    if (updateIndex <= 0)
      return false; 
    int versionSuffixIndex = version.indexOf('-', updateIndex + 1);
    if (versionSuffixIndex <= 0) {
      updateVersion = version.substring(updateIndex + 1);
    } else {
      updateVersion = version.substring(updateIndex + 1, versionSuffixIndex);
    } 
    try {
      return (Integer.parseInt(updateVersion) >= minimumUpdateVersion);
    } catch (NumberFormatException e) {
      return true;
    } 
  }
  
  private static File getAgentJarFile() throws URISyntaxException {
    ProtectionDomain protectionDomain = AgentMain.class.getProtectionDomain();
    CodeSource codeSource = protectionDomain.getCodeSource();
    if (codeSource == null)
      throw new IllegalStateException(String.format("Unable to get agent location, protection domain = %s", new Object[] { protectionDomain })); 
    URL location = codeSource.getLocation();
    if (location == null)
      throw new IllegalStateException(String.format("Unable to get agent location, code source = %s", new Object[] { codeSource })); 
    File agentJar = new File(location.toURI());
    if (!agentJar.getName().endsWith(".jar"))
      throw new IllegalStateException("Agent is not a jar file: " + agentJar); 
    return agentJar.getAbsoluteFile();
  }
}
