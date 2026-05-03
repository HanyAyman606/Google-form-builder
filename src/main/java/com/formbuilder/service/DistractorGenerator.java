package com.formbuilder.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates 3 plausible-but-wrong numeric distractors for a given correct answer.
 *
 * Strategy:
 *   - Parse the correct answer as a number (handles decimals, scientific notation)
 *   - Generate 3 wrong answers that are:
 *       • Close enough to look plausible
 *       • Far enough apart to be distinct
 *       • Rounded to the same number of decimal places as the original
 *       • Never negative if the original is positive
 *       • Never equal to each other or to the correct answer
 *
 * Examples:
 *   3.18  → 8.56, 4.2,  6.1
 *   320   → 300,  450,  180
 *   72.5  → 80,   26.5, 74
 *   6.25e19 → plausible multiples/fractions of the same magnitude
 */
public class DistractorGenerator {

    private static final Random RNG = new Random();

    /**
     * Generates exactly 3 wrong answer strings for the given correct answer string.
     * If the input is not numeric, returns 3 generic placeholders.
     *
     * @param correctAnswerText  the teacher-typed correct answer (e.g. "3.18", "320", "6.25×10^19")
     * @return list of exactly 3 distractor strings, never equal to correctAnswerText
     */
    public static List<String> generate(String correctAnswerText) {
        if (correctAnswerText == null || correctAnswerText.isBlank()) {
            return List.of("Option B", "Option C", "Option D");
        }

        // Try to parse as a number
        Double value = tryParse(correctAnswerText.trim());
        if (value == null) {
            // Not a simple number — can't generate smart distractors
            return List.of(
                correctAnswerText + " × 2",
                correctAnswerText + " / 2",
                correctAnswerText + " × 3"
            );
        }

        int decimals = countDecimals(correctAnswerText.trim());
        List<String> distractors = new ArrayList<>();
        int attempts = 0;

        while (distractors.size() < 3 && attempts < 100) {
            attempts++;
            double candidate = generateCandidate(value);

            // Round to same decimal places as original
            candidate = roundTo(candidate, decimals);

            // Must not be equal to correct answer or any already-generated distractor
            String candidateStr = format(candidate, decimals);
            if (candidateStr.equals(format(value, decimals))) continue;
            if (distractors.contains(candidateStr)) continue;
            if (candidate <= 0 && value > 0) continue; // avoid negatives for positive answers

            distractors.add(candidateStr);
        }

        // Fallback if we somehow couldn't generate enough
        while (distractors.size() < 3) {
            double fallback = value * (2 + distractors.size());
            distractors.add(format(roundTo(fallback, decimals), decimals));
        }

        return distractors;
    }

    // ── Core distractor generation ─────────────────────────────────────────

    private static double generateCandidate(double correct) {
        // Pick a random strategy each time
        int strategy = RNG.nextInt(6);
        double abs = Math.abs(correct);

        return switch (strategy) {
            // Multiply by a factor between 1.2 and 4.0
            case 0 -> correct * (1.2 + RNG.nextDouble() * 2.8);
            // Divide by a factor between 1.5 and 4.0
            case 1 -> correct / (1.5 + RNG.nextDouble() * 2.5);
            // Add 15-60% of the value
            case 2 -> correct + abs * (0.15 + RNG.nextDouble() * 0.45);
            // Subtract 15-60% of the value
            case 3 -> correct - abs * (0.15 + RNG.nextDouble() * 0.45);
            // Swap digits / nearby round number
            case 4 -> nearbyRoundNumber(correct);
            // Ratio-based: common physics mistake (e.g. forgot to square)
            case 5 -> correct * correct / (abs > 1 ? abs : 1.0 / abs);
            default -> correct * 2;
        };
    }

    /**
     * Generates a nearby "round" number — mimics the kind of wrong answer
     * students get when they make an arithmetic error.
     * E.g. 3.18 → 3.0 or 4.0, 320 → 360 or 280
     */
    private static double nearbyRoundNumber(double value) {
        double abs = Math.abs(value);
        double magnitude = Math.pow(10, Math.floor(Math.log10(abs)));
        // Round to nearest magnitude unit, then offset by 1-3 units
        double rounded = Math.round(value / magnitude) * magnitude;
        int offset = (RNG.nextBoolean() ? 1 : -1) * (1 + RNG.nextInt(3));
        return rounded + offset * magnitude;
    }

    // ── Parsing helpers ────────────────────────────────────────────────────

    private static Double tryParse(String s) {
        // Handle scientific notation variants: 6.25×10^19, 6.25x10^19, 6.25e19
        s = s.replaceAll("[×x]10\\^", "e")   // 6.25×10^19 → 6.25e19
             .replaceAll("×10", "e")
             .replaceAll("\\s", "");
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countDecimals(String s) {
        // Handle scientific notation — preserve significant figures
        if (s.toLowerCase().contains("e") || s.contains("×") || s.contains("x10")) {
            return 2; // default for sci notation
        }
        int dot = s.indexOf('.');
        if (dot < 0) return 0;
        return s.length() - dot - 1;
    }

    private static double roundTo(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static String format(double value, int decimals) {
        if (decimals == 0) {
            return String.valueOf((long) value);
        }
        return String.format("%." + decimals + "f", value);
    }
}