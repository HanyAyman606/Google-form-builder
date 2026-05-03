package com.formbuilder;

import com.formbuilder.model.QuestionData;
import com.formbuilder.service.JsonReaderService;
import com.formbuilder.service.PythonRunner;

import java.util.List;

public class TestPython {
    public static void main(String[] args) {
        try {
            // Step 1: Run Python
            PythonRunner.run("input/final Book1 part1 msT.pdf");

            // Step 2: Read JSON
            List<QuestionData> questions =
                    JsonReaderService.read("output/output.json");

            System.out.println("\n=== LOADED QUESTIONS ===");

            for (int i = 0; i < Math.min(5, questions.size()); i++) {
                System.out.println(questions.get(i));
            }

            System.out.println("\nTotal Questions: " + questions.size());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}