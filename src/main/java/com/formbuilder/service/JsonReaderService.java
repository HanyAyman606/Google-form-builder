package com.formbuilder.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formbuilder.model.QuestionData;

import java.io.File;
import java.util.List;

public class JsonReaderService {

    public static List<QuestionData> read(String path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        return mapper.readValue(
                new File(path),
                new TypeReference<List<QuestionData>>() {}
        );
    }
}