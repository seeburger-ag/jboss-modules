/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.modules;

import __redirected.__JAXPRedirected;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.LogManager;

import org.jboss.modules.log.JDKModuleLogger;

/**
 * The main entry point of JBoss Modules when run as a JAR on the command line.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author Jason T. Greene
 * @apiviz.exclude
 */
public final class Main {

    static {
        // Force initialization at the earliest possible point
        @SuppressWarnings("unused")
        long start = StartTimeHolder.START_TIME;
    }

    private static final String[] NO_STRINGS = new String[0];

    private final static String BISAS_PROPERTY_NAME = "bisas.pid";

    private Main() {
    }

    private static void usage() {
        System.out.println("Usage: java [-jvmoptions...] -jar " + getJarName() + ".jar [-options...] <module-spec> [args...]");
        System.out.println("       java [-jvmoptions...] -jar " + getJarName() + ".jar [-options...] -jar <jar-name> [args...]");
        System.out.println("       java [-jvmoptions...] -jar " + getJarName() + ".jar [-options...] -cp <class-path> <class-name> [args...]");
        System.out.println("       java [-jvmoptions...] -jar " + getJarName() + ".jar [-options...] -class <class-name> [args...]");
        System.out.println("where <module-spec> is a valid module specification string");
        System.out.println("and options include:");
        System.out.println("    -help         Display this message");
        System.out.println("    -modulepath <search path of directories>");
        System.out.println("    -mp <search path of directories>");
        System.out.println("                  A list of directories, separated by '" + File.pathSeparator + "', where modules may be located");
        System.out.println("                  If not specified, the value of the \"module.path\" system property is used");
        System.out.println("    -class        Specify that the final argument is a");
        System.out.println("                  class to load from the class path; not compatible with -jar");
        System.out.println("    -cp,-classpath <search path of archives or directories>");
        System.out.println("                  A search path for class files; implies -class");
        System.out.println("    -dep,-dependencies <module-spec>[,<module-spec>,...]");
        System.out.println("                  A list of module dependencies to add to the class path;");
        System.out.println("                  requires -class or -cp");
        System.out.println("    -jar          Specify that the final argument is the name of a");
        System.out.println("                  JAR file to run as a module; not compatible with -class");
        System.out.println("    -config <config-location>");
        System.out.println("                  The location of the module configuration.  Either -mp or -config");
        System.out.println("                  may be specified, but not both");
        System.out.println("    -jaxpmodule <module-name>");
        System.out.println("                  The default JAXP implementation to use of the JDK");
        System.out.println("    -version      Print version and exit\n");
    }

    /**
     * Run JBoss Modules.
     *
     * @param args the command-line arguments
     *
     * @throws Throwable if an error occurs
     */
    public static void main(String[] args) throws Throwable {
        final int argsLen = args.length;

        // ### SEEBURGER extension to terminate existing processes
        boolean killProcFlag = Boolean.getBoolean("terminate.existing.processes");
        if (killProcFlag)
        {
            //Terminate existing running processes if needed
            for (int i = 0; i < argsLen; i++)
            {
                if (args[i] != null && "org.jboss.as.process-controller".equals(args[i]))
                {
                    int terminated = terminateRunningProcesses();
                    System.out.println("Existing processes terminated " +
                                    ((terminated == 0) ? "successfully." : "with warnings."));
                    break;
                }
            }
        }
        // ### end of SEEBURGER extension to terminate existing processes

        String deps = null;
        String[] moduleArgs = NO_STRINGS;
        String modulePath = null;
        String configPath = null;
        String classpath = null;
        boolean jar = false;
        boolean classpathDefined = false;
        boolean classDefined = false;
        String moduleIdentifierOrExeName = null;
        ModuleIdentifier jaxpModuleIdentifier = null;
        for (int i = 0, argsLength = argsLen; i < argsLength; i++) {
            final String arg = args[i];
            try {
                if (arg.charAt(0) == '-') {
                    // it's an option
                    if ("-version".equals(arg)) {
                        System.out.println("JBoss Modules version " + getVersionString());
                        return;
                    } else if ("-help".equals(arg)) {
                        usage();
                        return;
                    } else if ("-modulepath".equals(arg) || "-mp".equals(arg)) {
                        if (modulePath != null) {
                            System.err.println("Module path may only be specified once");
                            System.exit(1);
                        }
                        if (configPath != null) {
                            System.err.println("Module path may not be specified with config path");
                            System.exit(1);
                        }
                        modulePath = args[++i];
                        System.setProperty("module.path", modulePath);
                    } else if ("-config".equals(arg)) {
                        if (configPath != null) {
                            System.err.println("Config file path may only be specified once");
                            System.exit(1);
                        }
                        if (modulePath != null) {
                            System.err.println("Module path may not be specified with config path");
                            System.exit(1);
                        }
                        configPath = args[++i];
                    } else if ("-jaxpmodule".equals(arg)) {
                        jaxpModuleIdentifier = ModuleIdentifier.fromString(args[++i]);
                    } else if ("-jar".equals(arg)) {
                        if (jar) {
                            System.err.println("-jar flag may only be specified once");
                            System.exit(1);
                        }
                        if (classpathDefined) {
                            System.err.println("-cp/-classpath may not be specified with -jar");
                            System.exit(1);
                        }
                        if (classDefined) {
                            System.err.println("-class may not be specified with -jar");
                            System.exit(1);
                        }
                        jar = true;
                    } else if ("-cp".equals(arg) || "-classpath".equals(arg)) {
                        if (classpathDefined) {
                            System.err.println("-cp or -classpath may only be specified once.");
                            System.exit(1);
                        }
                        if (classDefined) {
                            System.err.println("-class may not be specified with -cp/classpath");
                            System.exit(1);
                        }
                        if (jar) {
                            System.err.println("-cp/-classpath may not be specified with -jar");
                            System.exit(1);
                        }
                        classpathDefined = true;
                        classpath = args[++i];
                        AccessController.doPrivileged(new PropertyWriteAction("java.class.path", classpath));
                    } else if ("-dep".equals(arg) || "-dependencies".equals(arg)) {
                        if (deps != null) {
                            System.err.println("-dep or -dependencies may only be specified once.");
                            System.exit(1);
                        }
                        deps = args[++i];
                    } else if ("-class".equals(arg)) {
                        if (classDefined) {
                            System.err.println("-class flag may only be specified once");
                            System.exit(1);
                        }
                        if (classpathDefined) {
                            System.err.println("-class may not be specified with -cp/classpath");
                            System.exit(1);
                        }
                        if (jar) {
                            System.err.println("-class may not be specified with -jar");
                            System.exit(1);
                        }
                        classDefined = true;
                    } else if ("-logmodule".equals(arg)) {
                        System.out.println("WARNING: -logmodule is deprecated. Please use the system property 'java.util.logging.manager' or the 'java.util.logging.LogManager' service loader.");
                        i++;
                    } else {
                        System.err.printf("Invalid option '%s'\n", arg);
                        usage();
                        System.exit(1);
                    }
                } else {
                    // it's the module specification
                    moduleIdentifierOrExeName = arg;
                    int cnt = argsLen - i - 1;
                    moduleArgs = new String[cnt];
                    System.arraycopy(args, i + 1, moduleArgs, 0, cnt);
                    break;
                }
            } catch (IndexOutOfBoundsException e) {
                System.err.printf("Argument expected for option %s\n", arg);
                usage();
                System.exit(1);
            }
        }

        if (deps != null && ! classDefined && ! classpathDefined) {
            System.err.println("-deps may only be specified when -cp/-classpath or -class is in use");
            System.exit(1);
        }

        // run the module
        if (moduleIdentifierOrExeName == null) {
            if (classDefined || classpathDefined) {
                System.err.println("No class name specified");
            } else if (jar) {
                System.err.println("No JAR specified");
            } else {
                System.err.println("No module specified");
            }
            usage();
            System.exit(1);
        }
        final ModuleLoader loader;
        final ModuleLoader environmentLoader;
        if (configPath != null) {
            environmentLoader = ModuleXmlParser.parseModuleConfigXml(new File(configPath));
        } else {
            environmentLoader = DefaultBootModuleLoaderHolder.INSTANCE;
        }
        final ModuleIdentifier moduleIdentifier;
        if (jar) {
            loader = new JarModuleLoader(environmentLoader, new JarFile(moduleIdentifierOrExeName));
            moduleIdentifier = ((JarModuleLoader) loader).getMyIdentifier();
        } else if (classpathDefined || classDefined) {
            loader = new ClassPathModuleLoader(environmentLoader, moduleIdentifierOrExeName, classpath, deps);
            moduleIdentifier = ModuleIdentifier.CLASSPATH;
        } else {
            loader = environmentLoader;
            moduleIdentifier = ModuleIdentifier.fromString(moduleIdentifierOrExeName);
        }
        Module.initBootModuleLoader(loader);
        if (jaxpModuleIdentifier != null) {
            __JAXPRedirected.changeAll(jaxpModuleIdentifier, Module.getBootModuleLoader());
        } else {
            __JAXPRedirected.changeAll(moduleIdentifier, Module.getBootModuleLoader());
        }

        final Module module;
        try {
            module = loader.loadModule(moduleIdentifier);
        } catch (ModuleNotFoundException e) {
            e.printStackTrace(System.err);
            System.exit(1);
            return;
        }

        ModularURLStreamHandlerFactory.addHandlerModule(module);
        ModularContentHandlerFactory.addHandlerModule(module);

        final ModuleClassLoader bootClassLoader = module.getClassLoaderPrivate();
        setContextClassLoader(bootClassLoader);

        final String logManagerName = getServiceName(bootClassLoader, "java.util.logging.LogManager");
        if (logManagerName != null) {
            System.setProperty("java.util.logging.manager", logManagerName);
            if (LogManager.getLogManager().getClass() == LogManager.class) {
                System.err.println("WARNING: Failed to load the specified log manager class " + logManagerName);
            } else {
                Module.setModuleLogger(new JDKModuleLogger());
            }
        }

        final String mbeanServerBuilderName = getServiceName(bootClassLoader, "javax.management.MBeanServerBuilder");
        if (mbeanServerBuilderName != null) {
            System.setProperty("javax.management.builder.initial", mbeanServerBuilderName);
            // Initialize the platform mbean server
            ManagementFactory.getPlatformMBeanServer();
        }

        String pidName = null;
        if (moduleIdentifierOrExeName.contains("host"))
        {
            pidName = "host-controller.pid";
        }
        else if (moduleIdentifierOrExeName.contains("process"))
        {
            pidName = "process-controller.pid";
        }
        else
        {
            String instanceId = System.getProperty("instance.id");
            if (instanceId != null)
            {
                pidName = instanceId  + ".pid";
            }
        }

        if (pidName != null)
        {
            writePid(pidName);
        }

        try {
            ModuleLoader.installMBeanServer();
            module.run(moduleArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
        return;
    }

    private static String getServiceName(ClassLoader classLoader, String className) throws IOException {
        final InputStream stream = classLoader.getResourceAsStream("META-INF/services/" + className);
        if (stream != null) {
            try {
                final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                String line;
                while ((line = reader.readLine()) != null) {
                    final int i = line.indexOf('#');
                    if (i != -1) {
                        line = line.substring(0, i);
                    }
                    line = line.trim();
                    if (line.length() == 0) continue;
                    return line;
                }

            } finally {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        return null;
    }

    private static ClassLoader setContextClassLoader(final ClassLoader classLoader) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                public ClassLoader run() {
                    return doSetContextClassLoader(classLoader);
                }
            });
        }
        return doSetContextClassLoader(classLoader);
    }

    protected static ClassLoader doSetContextClassLoader(final ClassLoader classLoader) {
        try {
            return Thread.currentThread().getContextClassLoader();
        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }
    }

    private static final String JAR_NAME;
    private static final String VERSION_STRING;

    static {
        final Enumeration<URL> resources;
        String jarName = "(unknown)";
        String versionString = "(unknown)";
        try {
            final ClassLoader classLoader = Main.class.getClassLoader();
            resources = classLoader == null ? ModuleClassLoader.getSystemResources("META-INF/MANIFEST.MF") : classLoader.getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                final URL url = resources.nextElement();
                final InputStream stream = url.openStream();
                if (stream != null) try {
                    final Manifest manifest = new Manifest(stream);
                    final Attributes mainAttributes = manifest.getMainAttributes();
                    if (mainAttributes != null && "JBoss Modules".equals(mainAttributes.getValue("Specification-Title"))) {
                        jarName = mainAttributes.getValue("Jar-Name");
                        versionString = mainAttributes.getValue("Jar-Version");
                    }
                } finally {
                    try { stream.close(); } catch (Throwable ignored) {}
                }
            }
        } catch (IOException ignored) {
        }
        JAR_NAME = jarName;
        VERSION_STRING = versionString;
    }

    /**
     * Get the name of the JBoss Modules JAR.
     *
     * @return the name
     */
    public static String getJarName() {
        return JAR_NAME;
    }

    /**
     * Get the version string of JBoss Modules.
     *
     * @return the version string
     */
    public static String getVersionString() {
        return VERSION_STRING;
    }


    /**
     * ### SEEBURGER extension to terminate existing processes.
     * @return the exit value of the subprocess represented by this Process object. By convention, the value 0 indicates normal termination.
     */
    private static int terminateRunningProcesses()
    {
        String shutdownScript;
        String os = System.getProperty("os.name");
        if (os != null && os.toLowerCase().startsWith("windows"))
        {
            shutdownScript = "shutdown-bisas.bat";
        }
        else
        {
            shutdownScript = "shutdown-bisas.sh";
        }

        try
        {
            System.out.println("Terminating old running processes...");
            String path = System.getenv("JBOSS_HOME") + "/bin/";
            Process pr = Runtime.getRuntime().exec(path + shutdownScript+ " 0");
            return pr.waitFor();
        }
        catch (Exception exc)
        {
            exc.printStackTrace(System.err);
            return 1;
        }
    }


    private static void writePid(String pidName) throws Exception
    {
        String pid = ManagementFactory.getRuntimeMXBean().getName();

        if (null == pid || pid.length() < 1)
        {
            System.out.println("Cannot determine pid.");
            return;
        }

        int posSep = pid.indexOf('@');
        if (-1 != posSep)
        {
            pid = pid.substring(0, posSep);
        }

        try
        {
            Long.parseLong(pid);
        }
        catch (NumberFormatException ne)
        {
            System.out.println("Determined pid=" + pid + " is not usable because it contains non numeric characters.");
            return;
        }

        System.setProperty(BISAS_PROPERTY_NAME, pid);

        File pidFile = new File(pidName).getAbsoluteFile();
        pidFile.delete();

        File parentFile = pidFile.getParentFile();

        if ((parentFile != null) && !parentFile.exists())
        {
            parentFile.mkdirs();
        }

        FileOutputStream outStream = null;
        try
        {
            // this should use some locking and atomic renames
            outStream = new FileOutputStream(pidFile);
            outStream.write(pid.getBytes());
            outStream.close();
            outStream = null;

            System.out.println("Determined pid=" + pid + " was written to file=" + pidFile);
        }
        finally
        {
            if (null != outStream)
            {
                outStream.close();
            }
        }
    }
}
