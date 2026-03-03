package com.vlesscore.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ConsoleReader {

    private static final Logger log = LoggerFactory.getLogger(ConsoleReader.class);
    private final CommandProcessor processor;

    public ConsoleReader(CommandProcessor processor) {
        this.processor = processor;
    }

    public void start() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            System.out.print("> ");
            System.out.flush();

            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    boolean continueRunning = processor.process(line);
                    if (!continueRunning) break;
                }
                System.out.print("> ");
                System.out.flush();
            }
        } catch (Exception e) {
            log.error("Ошибка чтения консоли", e);
        }
    }
}