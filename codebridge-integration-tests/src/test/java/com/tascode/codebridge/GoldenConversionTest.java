package com.tascode.codebridge;

import com.tascode.codebridge.language.Language;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenConversionTest {
    private record Case(String id, Language from, Language to) { }
    private static final List<Case> CASES = List.of(
            new Case("c-cpp", Language.C, Language.CPP), new Case("c-java", Language.C, Language.JAVA),
            new Case("cpp-c", Language.CPP, Language.C), new Case("cpp-java", Language.CPP, Language.JAVA),
            new Case("java-c", Language.JAVA, Language.C), new Case("java-cpp", Language.JAVA, Language.CPP));

    @Test
    void allSixDirectionsMatchGoldenFiles() throws IOException {
        var pipeline = CodeBridge.createPipeline();
        for (Case testCase : CASES) {
            String input = read("golden/" + testCase.id() + "/input." + testCase.from().id());
            String expected = read("golden/" + testCase.id() + "/expected." + testCase.to().id());
            var result = pipeline.convert(testCase.from(), testCase.to(), input);
            assertTrue(result.successful(), () -> testCase.id() + ": " + result.diagnostics());
            assertEquals(expected, result.output().orElseThrow(), testCase.id());
        }
    }

    private static String read(String resource) throws IOException {
        try (var stream = GoldenConversionTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing golden fixture: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
