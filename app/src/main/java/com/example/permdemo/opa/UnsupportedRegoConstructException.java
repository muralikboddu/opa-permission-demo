package com.example.permdemo.opa;

/**
 * Thrown when PartialEvalTranslator sees a shape in OPA's compile response
 * it doesn't recognize, instead of silently emitting a wrong or partial
 * WHERE clause. The message always includes the raw JSON so you can see
 * exactly what OPA sent back.
 *
 * IMPORTANT: this translator's understanding of OPA's partial-evaluation
 * AST (the shape of `result.queries` from POST /v1/compile) was written
 * from OPA's documented, long-stable expression/term format, but could not
 * be verified against a live OPA server while this project was put
 * together (see README -- the sandbox it was built in couldn't reach
 * Docker Hub or download the OPA binary). If you hit this exception the
 * first time you run the real thing, it's telling you exactly where to
 * look: call GET /trades/_debug/compile?user=... , compare the raw JSON to
 * what this class expects, and adjust the walker below.
 */
public class UnsupportedRegoConstructException extends RuntimeException {
    public UnsupportedRegoConstructException(String message, Object rawJson) {
        super(message + " -- raw node: " + rawJson);
    }
}
