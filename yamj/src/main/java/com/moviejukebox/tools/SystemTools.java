/*
 *      Copyright (c) 2004-2016 YAMJ Members
 *      https://github.com/orgs/YAMJ/people
 *
 *      This file is part of the Yet Another Movie Jukebox (YAMJ) project.
 *
 *      YAMJ is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU General Public License as published by
 *      the Free Software Foundation, either version 3 of the License, or
 *      any later version.
 *
 *      YAMJ is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU General Public License for more details.
 *
 *      You should have received a copy of the GNU General Public License
 *      along with YAMJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 *      Web: https://github.com/YAMJ/yamj-v2
 *
 */
package com.moviejukebox.tools;

import static com.moviejukebox.tools.StringTools.formatFileSize;

import com.moviejukebox.tools.cache.CacheMemory;
import java.io.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SystemTools {

    private static final Logger LOG = LoggerFactory.getLogger(SystemTools.class);
    private static final boolean SHOW_MEMORY = PropertiesUtil.getBooleanProperty("mjb.showMemory", Boolean.FALSE);
    private static final long CACHE_MAX_SIZE = (PropertiesUtil.getLongProperty("mjb.cacheOffSize", 50) * 1024L * 1024L);

    private SystemTools() {
        throw new UnsupportedOperationException("Class cannot be instantiated");
    }

    /**
     * Show the memory available to the program and optionally try to force a
     * garbage collection
     *
     * @param showAll
     */
    public static void showMemory(boolean showAll) {

        /*
         * This will return Long.MAX_VALUE if there is no preset limit
         */
        long memoryMaximum = Runtime.getRuntime().maxMemory();
        long memoryAllocated = Runtime.getRuntime().totalMemory();
        long memoryFree = Runtime.getRuntime().freeMemory();
        float memoryPercentage = ((memoryFree / memoryMaximum) * 100F);

        if (SHOW_MEMORY) {
            if (showAll) {
                // Maximum amount of memory the JVM will attempt to use
                LOG.info("  Maximum memory: {}", (memoryMaximum == Long.MAX_VALUE ? "no limit" : formatFileSize(memoryMaximum)));

                // Total memory currently in use by the JVM
                LOG.info("Allocated memory: {}", formatFileSize(memoryAllocated));

                // Total amount of free memory available to the JVM
                LOG.info("     Free memory: {} ({}%)", formatFileSize(memoryFree), (int) memoryPercentage);
            } else {
                LOG.info("Memory - Maximum: {}, Allocated: {}, Free: {} ({}%)",
                        formatFileSize(memoryMaximum),
                        formatFileSize(memoryAllocated),
                        formatFileSize(memoryFree),
                        (int) memoryPercentage);
            }
        }

        // Check to see if we need to turn the cache off.
        if (memoryFree < CACHE_MAX_SIZE) {
            CacheMemory.purgeCache();
        }

        // Run garbage collection (if needed)
        System.gc();
    }

    /**
     * Show the memory available to the program and optionally try to force a
     * garbage collection
     */
    public static void showMemory() {
        showMemory(false);
    }

    /**
     * Helper method that throws an exception and saves it to the log as well.
     *
     * @param text
     */
    public static void logException(String text) {
        LOG.error("***** GENERATED EXCEPTION *****");
        Exception thrownException = new Exception(text);
        final Writer sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw, true);
        thrownException.printStackTrace(pw);

        try {
            sw.flush();
            sw.close();
            pw.flush();
            pw.close();
        } catch (IOException ex) {
            LOG.trace("Failed to close writers", ex);
        }

        LOG.error(sw.toString());

    }

    /**
     * Helper method to print the stack trace to the log file
     *
     * @param tw
     * @return
     */
    public static String getStackTrace(Throwable tw) {
        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter(sw, true);
        tw.printStackTrace(pw);
        pw.flush();
        sw.flush();
        return sw.toString();
    }

    /**
     * Check the 'lib' directory to see if any of the common API jars have
     * duplicates and warn if they do
     *
     * @return
     */
    public static boolean validateInstallation() {
        boolean installationIsValid = Boolean.TRUE;

        if (PropertiesUtil.getBooleanProperty("mjb.skipCheckJars", Boolean.FALSE)) {
            return installationIsValid;
        }

        // List of the jars to check for duplicates
        Map<String, List<String>> jarsToCheck = new HashMap<>();
        jarsToCheck.put("allocine-api", new ArrayList<String>());
        jarsToCheck.put("fanarttvapi", new ArrayList<String>());
        jarsToCheck.put("mjbsqldb", new ArrayList<String>());
        jarsToCheck.put("rottentomatoesapi", new ArrayList<String>());
        jarsToCheck.put("subbabaapi", new ArrayList<String>());
        jarsToCheck.put("themoviedbapi", new ArrayList<String>());
        jarsToCheck.put("thetvdbapi", new ArrayList<String>());
        jarsToCheck.put("traileraddictapi", new ArrayList<String>());
        jarsToCheck.put("tvrageapi", new ArrayList<String>());
        jarsToCheck.put("yamj", new ArrayList<String>());

        List<String> jarList = Arrays.asList(System.getProperty("java.class.path").split(";"));
        for (String currentJar : jarList) {
            // Only check the lib directory
            if (currentJar.startsWith("lib/")) {
                currentJar = currentJar.substring(4);
            } else {
                continue;
            }

            for (Map.Entry<String, List<String>> entry : jarsToCheck.entrySet()) {
                if (currentJar.contains(entry.getKey())) {
                    entry.getValue().add(currentJar);
                    if (entry.getValue().size() > 1) {
                        installationIsValid = Boolean.FALSE;
                    }
                    // No need to check for further matches
                }
            }
        }

        if (!installationIsValid) {
            LOG.error("WARNING: Your installation appears to be invalid.");
            LOG.error("WARNING: Please ensure you delete the 'lib' folder before you update!");
            LOG.error("WARNING: You will need to re-install YAMJ now to ensure correct running!");
            LOG.error("");
            LOG.error("The following duplicates were found:");

            for (Map.Entry<String, List<String>> entry : jarsToCheck.entrySet()) {
                if (entry.getValue().size() > 1) {
                    LOG.error("{}:", entry.getKey());
                    for (String dupJar : entry.getValue()) {
                        LOG.error("    - {}", dupJar);
                    }
                }
            }
        }

        return installationIsValid;
    }

}
