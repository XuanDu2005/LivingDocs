package com.livingdocs.modules.code.service;

import com.livingdocs.modules.code.service.CodeEntityExtractionService.ParsedEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The rules that turn a source file into the list of code entities.
 *
 * <p>These patterns are the platform's only view of a repository's code:
 * the drift detector compares a document against the entities listed
 * here, and the AI service is handed the source of exactly these
 * entities. A pattern that stops matching therefore shows up in the
 * product only as "this document references no code entities" — an
 * indirect symptom that is easy to misread as "the documentation is
 * fine".
 *
 * <p>The cases below come from a real repository: TypeScript sources
 * that use generic parameter lists and arrow functions.
 */
class CodeEntityExtractionServiceTest {

    private CodeEntityExtractionService service;

    @BeforeEach
    void setUp() {
        // The extractor only uses its collaborators to talk to GitHub and
        // the database, neither of which the parsing rules touch.
        service = new CodeEntityExtractionService(
                null, null, null, null, null, null, null);
    }

    private List<ParsedEntity> parseTypeScript(String content) {
        return service.parse(content, "typescript", "src/example.ts");
    }

    private Map<String, Integer> startLinesByName(List<ParsedEntity> entities) {
        return entities.stream().collect(Collectors.toMap(
                ParsedEntity::simpleName, ParsedEntity::startLine, (a, b) -> a));
    }

    @Test
    void extractsGenericFunction() {
        // A generic parameter list between the name and the argument
        // list used to hide the declaration completely, so the whole
        // file contributed no entities.
        List<ParsedEntity> entities = parseTypeScript("""
                export function useDebounce<T>(value: T, delayMs = 300): T {
                  return value;
                }
                """);

        assertEquals(1, entities.size());
        assertEquals("useDebounce", entities.get(0).simpleName());
        assertEquals("function", entities.get(0).type());
    }

    @Test
    void extractsGenericClass() {
        // The constructor is not an entity — neither side treats it as
        // one, because a constructor carries no return type.
        List<ParsedEntity> entities = parseTypeScript("""
                export class Box<T> {
                  constructor(private value: T) {}
                }
                """);

        assertEquals(1, entities.size());
        assertEquals("Box", entities.get(0).simpleName());
        assertEquals("class", entities.get(0).type());
    }

    @Test
    void extractsGenericFunctionWithMultipleTypeParameters() {
        List<ParsedEntity> entities = parseTypeScript("""
                export function pick<K extends string, V>(key: K): V | undefined {
                  return undefined;
                }
                """);

        assertEquals(1, entities.size());
        assertEquals("pick", entities.get(0).simpleName());
    }

    @Test
    void extractsArrowFunction() {
        List<ParsedEntity> entities = parseTypeScript("""
                export const double = (n: number): number => n * 2;
                """);

        assertEquals(1, entities.size());
        assertEquals("double", entities.get(0).simpleName());
        assertEquals("function", entities.get(0).type());
    }

    @Test
    void extractsGenericArrowFunction() {
        List<ParsedEntity> entities = parseTypeScript("""
                export const identity = <T,>(value: T): T => value;
                """);

        assertEquals(1, entities.size());
        assertEquals("identity", entities.get(0).simpleName());
    }

    @Test
    void ignoresPlainConstBinding() {
        // A `const` that is not a function is not an entity.
        List<ParsedEntity> entities = parseTypeScript("""
                export const count = 0;
                export const timer = setTimeout(() => {}, 100);
                """);

        assertTrue(entities.isEmpty(), "expected no entities, got " + entities);
    }

    @Test
    void recordsTheDeclarationOwnLine() {
        // The patterns start with `^\s*`, and in MULTILINE mode `\s`
        // also matches the newline that ended the previous line — so
        // anchoring on the start of the whole match put every entity one
        // line too early.
        List<ParsedEntity> entities = parseTypeScript("""
                /**
                 * A stat card.
                 */
                export interface StatItem {
                  label: string;
                }

                export function StatCard({ label }: StatItem) {
                  return label;
                }
                """);

        Map<String, Integer> lines = startLinesByName(entities);
        assertEquals(4, lines.get("StatItem"));
        assertEquals(8, lines.get("StatCard"));
    }

    @Test
    void recordsTheWholeBodyOfADeclaration() {
        List<ParsedEntity> entities = parseTypeScript("""
                export function formatDate(date: Date): string {
                  return date.toLocaleDateString('en-US', {
                    year: 'numeric',
                  });
                }
                """);

        ParsedEntity entity = entities.get(0);
        assertEquals("formatDate", entity.simpleName());
        assertEquals(1, entity.startLine());
        assertTrue(entity.endLine() >= 5,
                "the body must be included, but the range ended at line " + entity.endLine());
    }

    @Test
    void doesNotTreatControlFlowAsMethods() {
        // `if (x) {` and `for (const x of y) {` also look like an
        // indented name followed by an opening parenthesis, but they are
        // not entities.
        List<ParsedEntity> entities = parseTypeScript("""
                export class ApiClient {
                  async get<T>(path: string): Promise<T> {
                    if (path) {
                      for (const part of path.split('/')) {
                        await part;
                      }
                      while (loading) {
                        await tick();
                      }
                    }
                    return null as unknown as T;
                  }
                }
                """);

        Map<String, Integer> lines = startLinesByName(entities);
        assertEquals(List.of("ApiClient", "get"),
                entities.stream().map(ParsedEntity::simpleName).toList());
        assertEquals(2, lines.get("get"));
    }

    @Test
    void stillExtractsJavaEntities() {
        // The TypeScript patterns must not have changed the Java ones.
        List<ParsedEntity> entities = service.parse("""
                package com.example;

                public class Greeter {
                  public String greet(String name) {
                    return "Hello, " + name;
                  }
                }
                """, "java", "src/Greeter.java");

        Map<String, Integer> lines = startLinesByName(entities);
        assertEquals(3, lines.get("Greeter"));
        assertEquals(4, lines.get("greet"));
        assertFalse(entities.isEmpty());
    }

    @Test
    void stillExtractsPythonEntities() {
        List<ParsedEntity> entities = service.parse("""
                class Calculator:
                    def add(self, x: int) -> int:
                        return x
                """, "python", "tools/calc.py");

        Map<String, Integer> lines = startLinesByName(entities);
        assertEquals(1, lines.get("Calculator"));
        assertEquals(2, lines.get("add"));
    }
}
