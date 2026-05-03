package com.formbuilder.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs questions_divider.py with the given PDF path and optional extra arguments.
 *
 * v8: Added overload that accepts extra CLI args such as:
 *   --page-from 3 --page-to 7
 * Used by ImportPanelController when the teacher selects a page range.
 */
public class PythonRunner {

    /**
     * Runs questions_divider.py.
     *
     * @param pdfPath   absolute path to the PDF file
     * @param scriptDir directory where questions_divider.py lives
     * @param extraArgs additional CLI arguments (e.g. ["--page-from","3","--page-to","7"])
     */
    public static void run(String pdfPath, File scriptDir, List<String> extraArgs) throws Exception {
        String pythonCmd = findPython();

        File outputDir = new File(scriptDir, "output");
        if (!outputDir.exists()) outputDir.mkdirs();

        // Build the command:  python questions_divider.py <pdf> <outdir> [extra...]
        List<String> cmd = new ArrayList<>();
        cmd.add(pythonCmd);
        cmd.add("questions_divider.py");
        cmd.add(pdfPath);
        cmd.add(outputDir.getAbsolutePath());
        if (extraArgs != null) cmd.addAll(extraArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(scriptDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            System.out.println("=== PYTHON OUTPUT ===");
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        System.out.println("=== PYTHON DONE (exit code: " + exitCode + ") ===");

        if (exitCode != 0) {
            throw new RuntimeException(
                "Python script failed (exit code " + exitCode + ").\n"
                + "Output:\n" + output);
        }
    }

    /**
     * Convenience overload — no extra args (full PDF).
     */
    public static void run(String pdfPath, File scriptDir) throws Exception {
        run(pdfPath, scriptDir, null);
    }

    /**
     * Legacy overload — current working directory as scriptDir, no extra args.
     * Kept for TestPython.java compatibility.
     */
    public static void run(String pdfPath) throws Exception {
        run(pdfPath, new File("."), null);
    }

    private static String findPython() {
        for (String cmd : new String[]{"python", "python3"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version")
                        .redirectErrorStream(true).start();
                p.waitFor();
                if (p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return "python";
    }
}