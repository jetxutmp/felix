/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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
package org.apache.felix.framework.cache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Map;
import java.util.zip.ZipEntry;

import org.apache.felix.framework.Logger;
import org.apache.felix.framework.util.ZipFileX;
import org.apache.felix.framework.util.StringMap;
import org.apache.felix.framework.util.Util;
import org.apache.felix.framework.resolver.Content;

/**
 * <p>
 * This class implements a bundle archive revision for a standard bundle
 * JAR file. The specified location is the URL of the JAR file. By default,
 * the associated JAR file is copied into the revision's directory on the
 * file system, but it is possible to mark the JAR as 'by reference', which
 * will result in the bundle JAR be used 'in place' and not being copied. In
 * either case, some of the contents may be extracted into the revision
 * directory, such as embedded JAR files and native libraries.
 * </p>
**/
class JarRevision extends BundleRevision
{
    private static final transient String BUNDLE_JAR_FILE = "bundle.jar";

    private File m_bundleFile = null;
    private final ZipFileX m_zipFile;

    public JarRevision(
        Logger logger, Map configMap, File revisionRootDir,
        String location, boolean byReference)
        throws Exception
    {
        this(logger, configMap, revisionRootDir, location, byReference, null);
    }

    public JarRevision(
        Logger logger, Map configMap, File revisionRootDir, String location,
        boolean byReference, InputStream is)
        throws Exception
    {
        super(logger, configMap, revisionRootDir, location);

        if (byReference)
        {
            m_bundleFile = new File(location.substring(
                location.indexOf(BundleArchive.FILE_PROTOCOL)
                    + BundleArchive.FILE_PROTOCOL.length()));
        }
        else
        {
            m_bundleFile = new File(getRevisionRootDir(), BUNDLE_JAR_FILE);
        }

        // Save and process the bundle JAR.
        initialize(byReference, is);

        // Open shared copy of the JAR file.
        ZipFileX zipFile = null;
        try
        {
            // Open bundle JAR file.
            zipFile = BundleCache.getSecureAction().openZipFile(m_bundleFile);
            // Error if no jar file.
            if (zipFile == null)
            {
                throw new IOException("No JAR file found.");
            }
            m_zipFile = zipFile;
        }
        catch (Exception ex)
        {
            if (zipFile != null) zipFile.close();
            throw ex;
        }
    }

    public Map getManifestHeader() throws Exception
    {
        // Create a case insensitive map of manifest attributes.
        Map headers = new StringMap(false);
        // Read and parse headers.
        getMainAttributes(headers, m_zipFile);
        return headers;
    }

    public synchronized Content getContent() throws Exception
    {
        return new JarContent(getLogger(), getConfig(), this, getRevisionRootDir(),
            m_bundleFile, m_zipFile);
    }

    protected void close() throws Exception
    {
        m_zipFile.close();
    }

    //
    // Private methods.
    //

    private void initialize(boolean byReference, InputStream is)
        throws Exception
    {
        try
        {
            // If the revision directory does not exist, then create it.
            if (!BundleCache.getSecureAction().fileExists(getRevisionRootDir()))
            {
                if (!BundleCache.getSecureAction().mkdir(getRevisionRootDir()))
                {
                    getLogger().log(
                        Logger.LOG_ERROR,
                        getClass().getName() + ": Unable to create revision directory.");
                    throw new IOException("Unable to create archive directory.");
                }

                if (!byReference)
                {
                    URLConnection conn = null;
                    try
                    {
                        if (is == null)
                        {
                            // Do it the manual way to have a chance to
                            // set request properties such as proxy auth.
                            URL url = BundleCache.getSecureAction().createURL(
                                null, getLocation(), null);
                            conn = url.openConnection();

                            // Support for http proxy authentication.
                            String auth = BundleCache.getSecureAction()
                                .getSystemProperty("http.proxyAuth", null);
                            if ((auth != null) && (auth.length() > 0))
                            {
                                if ("http".equals(url.getProtocol()) ||
                                    "https".equals(url.getProtocol()))
                                {
                                    String base64 = Util.base64Encode(auth);
                                    conn.setRequestProperty(
                                        "Proxy-Authorization", "Basic " + base64);
                                }
                            }
                            is = BundleCache.getSecureAction()
                                .getURLConnectionInputStream(conn);
                        }

                        // Save the bundle jar file.
                        BundleCache.copyStreamToFile(is, m_bundleFile);
                    }
                    finally
                    {
                        // This is a hack to fix an issue on Android, where
                        // HttpURLConnections are not properly closed. (FELIX-2728)
                        if ((conn != null) && (conn instanceof HttpURLConnection))
                        {
                            ((HttpURLConnection) conn).disconnect();
                        }
                    }
                }
            }
        }
        finally
        {
            if (is != null) is.close();
        }
    }

    private static final ThreadLocal m_defaultBuffer = new ThreadLocal();
    private static final int DEFAULT_BUFFER = 1024 * 64;

    private static void getMainAttributes(Map result, ZipFileX zipFile) throws Exception
    {
        ZipEntry entry = zipFile.getEntry("META-INF/MANIFEST.MF");
        SoftReference ref = (SoftReference) m_defaultBuffer.get();
        byte[] bytes = null;
        if (ref != null)
        {
            bytes = (byte[]) ref.get();
        }
        int size = (int) entry.getSize();
        if (bytes == null)
        {
            bytes = new byte[size > DEFAULT_BUFFER ? size : DEFAULT_BUFFER];
            m_defaultBuffer.set(new SoftReference(bytes));
        }
        else if (size > bytes.length)
        {
            bytes = new byte[size];
            m_defaultBuffer.set(new SoftReference(bytes));
        }

        InputStream is = null;
        try
        {
            is = zipFile.getInputStream(entry);
            int i = is.read(bytes);
            while (i < size)
            {
                i += is.read(bytes, i, bytes.length - i);
            }
        }
        finally
        {
            is.close();
        }

        String key = null;
        int last = 0;
        int current = 0;
        for (int i = 0; i < size; i++)
        {
            if (bytes[i] == '\r')
            {
                if ((i + 1 < size) && (bytes[i + 1] == '\n'))
                {
                    continue;
                }
            }
            if (bytes[i] == '\n')
            {
                if ((i + 1 < size) && (bytes[i + 1] == ' '))
                {
                    i++;
                    continue;
                }
            }
            if ((key == null) && (bytes[i] == ':'))
            {
                key = new String(bytes, last, (current - last), "UTF-8");
                if ((i + 1 < size) && (bytes[i + 1] == ' '))
                {
                    last = current + 1;
                    continue;
                }
                else
                {
                    throw new Exception(
                        "Manifest error: Missing space separator - " + key);
                }
            }
            if (bytes[i] == '\n')
            {
                if ((last == current) && (key == null))
                {
                    break;
                }
                String value = new String(bytes, last, (current - last), "UTF-8");
                if (key == null) 
                {
                    throw new Exception("Manifst error: Missing attribute name - " + value);
                }
                else if (result.put(key, value) != null)
                {
                    throw new Exception("Manifst error: Duplicate attribute name - " + key);
                }
                last = current;
                key = null;
            }
            else
            {
                bytes[current++] = bytes[i];
            }
        }
    }
}