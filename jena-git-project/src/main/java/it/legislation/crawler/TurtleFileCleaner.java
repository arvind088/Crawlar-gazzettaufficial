package it.legislation.crawler;

import java.io.*;
import java.nio.file.*;
import java.util.regex.*;

public class TurtleFileCleaner {

    public static void main(String[] args) {
        String inputPath = "eli_metadata.ttl"; // Your input TTL file
        String outputPath = "eli_metadata_cleaned.ttl"; // Cleaned output

        try (BufferedReader reader = Files.newBufferedReader(Paths.get(inputPath));
             BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputPath))) {

            String line;
            String currentSubject = null;
            boolean insideMultilineLiteral = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                // Handle prefixes, comments, and empty lines
                if (trimmed.isEmpty() || trimmed.startsWith("@prefix") || trimmed.startsWith("#")) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                // Multiline literal handling
                if (insideMultilineLiteral) {
                    writer.write(line);
                    writer.newLine();
                    if (trimmed.endsWith("\"\"\"")) {
                        insideMultilineLiteral = false;
                    }
                    continue;
                }
                if (trimmed.startsWith("\"\"\"") && !trimmed.endsWith("\"\"\"")) {
                    insideMultilineLiteral = true;
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                // Detect a new subject line
                if (trimmed.startsWith("<") && trimmed.contains(">")) {
                    int end = trimmed.indexOf(">");
                    currentSubject = trimmed.substring(0, end + 1);
                    // Fix trailing semicolon or missing period
                    if (trimmed.endsWith(";")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1).trim() + " .";
                    } else if (!trimmed.endsWith(".")) {
                        trimmed = trimmed + " .";
                    }
                    writer.write(trimmed);
                    writer.newLine();
                    continue;
                }

                // Predicate lines that start without subject
                if ((trimmed.startsWith("eli:") || trimmed.startsWith("dct:") || trimmed.startsWith("xsd:")) && currentSubject != null) {
                    if (trimmed.endsWith(";")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1).trim() + " .";
                    } else if (!trimmed.endsWith(".")) {
                        trimmed += " .";
                    }
                    writer.write(currentSubject + " " + trimmed);
                    writer.newLine();
                    continue;
                }

                // Skip stray periods
                if (trimmed.equals(".")) continue;

                // Final fallback: try writing the line as-is
                writer.write(trimmed);
                writer.newLine();
            }

            System.out.println("✅ Turtle file cleaned and saved to: " + outputPath);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}