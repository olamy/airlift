/*
 * Copyright 2010 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.log;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;
import io.airlift.log.RollingFileMessageOutput.CompressionType;
import io.airlift.units.DataSize;

import javax.annotation.concurrent.GuardedBy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.Maps.fromProperties;
import static io.airlift.log.RollingFileMessageOutput.createRollingFileHandler;
import static io.airlift.log.SocketMessageOutput.createSocketHandler;
import static java.lang.String.format;
import static java.util.regex.Matcher.quoteReplacement;

/**
 * Initializes the logging subsystem.
 * <p>
 * java.util.Logging, System.out and System.err are tunneled through the logging system.
 * <p>
 * System.out and System.err are assigned to loggers named "stdout" and "stderr", respectively.
 */
public class Logging
{
    private static final Logger log = Logger.get(Logging.class);
    private static final String ROOT_LOGGER_NAME = "";
    private static final java.util.logging.Logger ROOT = java.util.logging.Logger.getLogger("");
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{ENV:([a-zA-Z][a-zA-Z0-9_]*)}");

    private static Logging instance;

    // hard reference to loggers for which we set the level
    @GuardedBy("this")
    private final Map<String, java.util.logging.Logger> loggers = new HashMap<>();

    @GuardedBy("this")
    private OutputStreamHandler consoleHandler;

    /**
     * Sets up default logging:
     * <p>
     * - INFO level
     * - Log entries are written to stderr
     *
     * @return the logging system singleton
     */
    public static synchronized Logging initialize()
    {
        if (instance == null) {
            instance = new Logging();
        }

        return instance;
    }

    private Logging()
    {
        ROOT.setLevel(Level.INFO.toJulLevel());
        for (Handler handler : ROOT.getHandlers()) {
            ROOT.removeHandler(handler);
        }

        enableConsole();
        log.info("Logging to stderr");

        redirectStdStreams();
    }

    private static void redirectStdStreams()
    {
        System.setOut(new PrintStream(new LoggingOutputStream(Logger.get("stdout")), true));
        System.setErr(new PrintStream(new LoggingOutputStream(Logger.get("stderr")), true));
    }

    private synchronized void enableConsole()
    {
        consoleHandler = new OutputStreamHandler(System.err);
        ROOT.addHandler(consoleHandler);
    }

    public synchronized void disableConsole()
    {
        log.info("Disabling stderr output");
        ROOT.removeHandler(consoleHandler);
        consoleHandler = null;
    }

    public void logToFile(boolean legacyLoggerImplementation, String logPath, int maxHistory, DataSize maxFileSize, DataSize maxTotalSize, CompressionType compressionType, Formatter formatter)
    {
        log.info("Logging to %s", logPath);

        Handler handler;
        if (legacyLoggerImplementation) {
            handler = new LegacyRollingFileHandler(logPath, maxHistory, maxFileSize.toBytes(), formatter);
        }
        else {
            handler = createRollingFileHandler(logPath, maxFileSize, maxTotalSize, compressionType, formatter);
        }
        ROOT.addHandler(handler);
    }

    private void logToSocket(String logPath, Formatter formatter)
    {
        if (!logPath.startsWith("tcp://") || logPath.lastIndexOf("/") > 6) {
            throw new IllegalArgumentException("LogPath for sockets must begin with tcp:// and not contain any path component.");
        }
        HostAndPort hostAndPort = HostAndPort.fromString(logPath.replace("tcp://", ""));
        Handler handler = createSocketHandler(hostAndPort, formatter);
        ROOT.addHandler(handler);
    }

    public Level getRootLevel()
    {
        return getLevel(ROOT_LOGGER_NAME);
    }

    public void setRootLevel(Level newLevel)
    {
        setLevel(ROOT_LOGGER_NAME, newLevel);
    }

    public void setLevels(File file)
            throws IOException
    {
        Properties properties = new Properties();
        try (InputStream inputStream = new FileInputStream(file)) {
            properties.load(inputStream);
        }

        fromProperties(properties).forEach((loggerName, value) ->
                setLevel(loggerName, Level.valueOf(value.toUpperCase(Locale.US))));
    }

    public Level getLevel(String loggerName)
    {
        return getEffectiveLevel(java.util.logging.Logger.getLogger(loggerName));
    }

    private static Level getEffectiveLevel(java.util.logging.Logger logger)
    {
        java.util.logging.Level level = logger.getLevel();
        if (level == null) {
            java.util.logging.Logger parent = logger.getParent();
            if (parent != null) {
                return getEffectiveLevel(parent);
            }
        }
        if (level == null) {
            return Level.OFF;
        }
        return Level.fromJulLevel(level);
    }

    public synchronized void clearLevel(String loggerName)
    {
        java.util.logging.Logger logger = loggers.remove(loggerName);
        if (logger != null) {
            logger.setLevel(null);
        }
    }

    public synchronized void setLevel(String loggerName, Level level)
    {
        loggers.computeIfAbsent(loggerName, java.util.logging.Logger::getLogger)
                .setLevel(level.toJulLevel());
    }

    public Map<String, Level> getAllLevels()
    {
        ImmutableSortedMap.Builder<String, Level> levels = ImmutableSortedMap.naturalOrder();
        for (String loggerName : Collections.list(LogManager.getLogManager().getLoggerNames())) {
            java.util.logging.Level level = java.util.logging.Logger.getLogger(loggerName).getLevel();
            if (level != null) {
                levels.put(loggerName, Level.fromJulLevel(level));
            }
        }
        return levels.build();
    }

    public void configure(LoggingConfiguration config)
            throws IOException
    {
        Map<String, String> systemContext = ImmutableMap.of();
        if (config.getAdditionalFieldsFile() != null) {
            Properties properties = new Properties();
            try (InputStream inputStream = new FileInputStream(new File(config.getAdditionalFieldsFile()))) {
                properties.load(inputStream);
            }
            systemContext = replaceEnvironmentVariables(Maps.fromProperties(properties));
        }

        if (config.getLogPath() != null) {
            if (config.getLogPath().startsWith("tcp://")) {
                logToSocket(config.getLogPath(), config.getFormat().createFormatter(systemContext));
            }
            else {
                logToFile(
                        config.isLegacyLoggerImplementationEnabled(),
                        config.getLogPath(),
                        config.getMaxHistory(),
                        config.getMaxSize(),
                        config.getMaxTotalSize(),
                        config.getCompression(),
                        config.getFormat().createFormatter(systemContext));
            }
        }

        if (!config.isConsoleEnabled()) {
            disableConsole();
        }

        if (config.getLevelsFile() != null) {
            try {
                setLevels(new File(config.getLevelsFile()));
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static Map<String, String> replaceEnvironmentVariables(
            Map<String, String> properties)
    {
        ArrayList<String> errors = new ArrayList<>();
        Map<String, String> replaced = new HashMap<>();
        properties.forEach((propertyKey, propertyValue) -> {
            StringBuilder replacedPropertyValue = new StringBuilder();
            Matcher matcher = ENV_PATTERN.matcher(propertyValue);
            while (matcher.find()) {
                String envName = matcher.group(1);
                String envValue = System.getenv().get(envName);
                if (envValue == null) {
                    errors.add(format("Configuration property '%s' references unset environment variable '%s'", propertyKey, envName));
                    return;
                }
                matcher.appendReplacement(replacedPropertyValue, quoteReplacement(envValue));
            }
            matcher.appendTail(replacedPropertyValue);
            replaced.put(propertyKey, replacedPropertyValue.toString());
        });
        if (errors.size() > 0) {
            throw new RuntimeException("Environment variables were referenced in configuration properties for system logging context, but unused:\n" + Joiner.on("\n").join(errors));
        }
        return replaced;
    }
}
