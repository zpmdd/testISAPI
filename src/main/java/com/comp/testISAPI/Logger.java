package com.comp.testISAPI;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 简单日志工具类
 * - 同时输出到控制台和文件
 * - 日志文件按日期存放在 log 目录
 * - 支持 DEBUG, INFO, WARN, ERROR 四个级别
 */
public class Logger {

    public enum Level {
        DEBUG(0), INFO(1), WARN(2), ERROR(3);

        private final int value;

        Level(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    private static final String LOG_DIR = "./log";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static Level currentLevel = Level.DEBUG; // 默认输出所有级别
    private static PrintWriter fileWriter = null;
    private static String currentLogDate = null;

    private final String className;

    // 静态初始化
    static {
        // 创建日志目录
        File logDir = new File(LOG_DIR);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        initLogFile();
    }

    private Logger(String className) {
        this.className = className;
    }

    /**
     * 获取 Logger 实例
     */
    public static Logger getLogger(Class<?> clazz) {
        return new Logger(clazz.getSimpleName());
    }

    /**
     * 设置日志级别
     */
    public static void setLevel(Level level) {
        currentLevel = level;
    }

    /**
     * 初始化日志文件
     * 强制使用 UTF-8 编码，确保跨平台（Windows/Mac/Linux）日志文件兼容
     */
    private static synchronized void initLogFile() {
        String today = DATE_FORMAT.format(new Date());

        // 如果日期变了，创建新的日志文件
        if (!today.equals(currentLogDate)) {
            if (fileWriter != null) {
                fileWriter.close();
            }

            currentLogDate = today;
            String logFileName = LOG_DIR + "/isapi_" + today + ".log";

            try {
                // 强制使用 UTF-8 编码，解决 Windows/Mac 跨平台乱码问题
                fileWriter = new PrintWriter(new BufferedWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(logFileName, true), 
                                StandardCharsets.UTF_8)), true);
            } catch (IOException e) {
                System.err.println("无法创建日志文件: " + e.getMessage());
            }
        }
    }

    /**
     * 写入日志
     */
    private void log(Level level, String message, Throwable throwable) {
        if (level.getValue() < currentLevel.getValue()) {
            return;
        }

        // 检查是否需要切换日志文件（跨天）
        initLogFile();

        String timestamp = TIME_FORMAT.format(new Date());
        String threadName = Thread.currentThread().getName();
        String logLine = String.format("[%s] [%s] [%s] [%s] %s",
                timestamp, level.name(), threadName, className, message);

        // 输出到控制台
        if (level == Level.ERROR || level == Level.WARN) {
            System.err.println(logLine);
        } else {
            System.out.println(logLine);
        }

        // 输出到文件
        if (fileWriter != null) {
            fileWriter.println(logLine);
            if (throwable != null) {
                throwable.printStackTrace(fileWriter);
            }
            fileWriter.flush();
        }

        // 如果有异常，也打印到控制台
        if (throwable != null) {
            throwable.printStackTrace();
        }
    }

    // ============ 日志方法 ============

    public void debug(String message) {
        log(Level.DEBUG, message, null);
    }

    public void debug(String format, Object... args) {
        log(Level.DEBUG, String.format(format, args), null);
    }

    public void info(String message) {
        log(Level.INFO, message, null);
    }

    public void info(String format, Object... args) {
        log(Level.INFO, String.format(format, args), null);
    }

    public void warn(String message) {
        log(Level.WARN, message, null);
    }

    public void warn(String format, Object... args) {
        log(Level.WARN, String.format(format, args), null);
    }

    public void warn(String message, Throwable t) {
        log(Level.WARN, message, t);
    }

    public void error(String message) {
        log(Level.ERROR, message, null);
    }

    public void error(String format, Object... args) {
        log(Level.ERROR, String.format(format, args), null);
    }

    public void error(String message, Throwable t) {
        log(Level.ERROR, message, t);
    }

    /**
     * 关闭日志
     */
    public static void close() {
        if (fileWriter != null) {
            fileWriter.close();
            fileWriter = null;
        }
    }
}
