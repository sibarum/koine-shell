package sibarum.koine.lang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckerTest {

    private static final Library LIBRARY = StandardLibrary.INSTANCE;

    // ---- the examples in docs/language.md, and the standard library ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"preflight", "Python", "Clock", "Process"})
    void theExamplesCheck(String name) {
        assertEquals(List.of(), problems(name + ".koine", resource("examples/" + name + ".koine")));
    }

    @Test
    void theStandardLibraryChecks() {
        for (String name : StandardLibrary.MODULES)
            assertEquals(List.of(), problems(name + ".koine", LIBRARY.module(name).orElseThrow()), name);
    }

    @Test
    void matchesTakesAPatternWrittenOut() {
        assertEquals(List.of("s.koine:3:20: matches takes its pattern as a string written out, with nothing inserted"),
                problems("s.koine", """
                script S<OS>
                  let p = '^a'
                  if 'abc'.matches(p)
                    print 'yes'
                  if 'abc'.matches('^a')
                    print 'yes'
                """));
    }

    /** Changing one thing in preflight is caught, which shows the clean result above is not the checker seeing nothing. */
    @Test
    void aMistakeInPreflightIsCaught() {
        String preflight = resource("examples/preflight.koine");
        assertEquals(List.of("preflight.koine:60:9: no name linuxApp"),
                problems("preflight.koine", preflight.replace("linuxApps.add(app.name)", "linuxApp.add(app.name)")));
        assertEquals(List.of("preflight.koine:41:48: Process.start takes String for log, not Number"),
                problems("preflight.koine", preflight.replace("Process.start(exe, log)", "Process.start(exe, seconds)")));
        assertEquals(List.of("preflight.koine:47:58: only a String can be inserted into a string, not Number; convert "
                        + "it with .text"),
                problems("preflight.koine", preflight.replace("{seconds.text}s", "{seconds}s")));
        // Every Json.read, including the one in linuxBuildProblems, which is checked at its first call.
        List<String> bootstrap = problems("preflight.koine",
                preflight.replace("script Preflight<Windows>", "bootstrap script Preflight<OS>"));
        assertEquals(List.of("13:20", "32:22", "58:22", "136:22", "90:22", "94:24"),
                bootstrap.stream().map(p -> p.split(":")[1] + ":" + p.split(":")[2]).toList());
        assertEquals("preflight.koine:13:20: Json.read is bundled on Linux, Mac, and a bootstrap script may use only "
                + "native members", bootstrap.getFirst());
    }

    // ---- names --------------------------------------------------------------------------------------------------

    @Test
    void namesAreIntroducedWithLetAndLiveUntilTheirBlockEnds() {
        assertEquals(List.of(
                "s.koine:3:3: no variable count; introduce it with let count = …",
                "s.koine:4:3: total is already defined; reassign it with total = …",
                "s.koine:7:9: no name inner"), problems("s.koine", """
                script S<OS>
                  let total = 1
                  count = 2
                  let total = 3
                  if total == 1
                    let inner = 'x'
                  print inner
                """));
    }

    @Test
    void aFunctionSeesOnlyWhatItIsPassed() {
        assertEquals(List.of("s.koine:5:11: no name outside"), problems("s.koine", """
                script S<OS>
                  let outside = 'x'
                  print greet('y')
                  def greet(name)
                    print outside
                    return name
                """));
    }

    // ---- types --------------------------------------------------------------------------------------------------

    @Test
    void valuesHaveTheTypesTheirUseNeeds() {
        assertEquals(List.of(
                "s.koine:3:6: a condition must be a Bool, not String",
                "s.koine:5:9: print takes a String, not Number",
                "s.koine:6:15: Clock.sleep takes Number for seconds, not String",
                "s.koine:7:10: String and Number cannot be compared",
                "s.koine:9:18: the list holds String, not Number"), problems("s.koine", """
                script S<OS>
                  let n = 1
                  if 'yes'
                    print 'no'
                  print n
                  Clock.sleep('3')
                  if 'a' == n
                    print 'x'
                  let xs = ['a', 2]
                """));
    }

    @Test
    void anEmptyListOrMapTakesTheTypeOfWhatGoesIn() {
        assertEquals(List.of("s.koine:4:10: the list holds String, not Number",
                "s.koine:7:12: the map's values are String, not Bool"), problems("s.koine", """
                script S<OS>
                  let xs = []
                  xs.add('a')
                  xs.add(1)
                  let m = {}
                  m['a'] = 'b'
                  m['c'] = true
                """));
    }

    @Test
    void membersAreLookedUpByModuleOrByTheValuesType() {
        assertEquals(List.of(
                "s.koine:2:14: Path has no member jion",
                "s.koine:3:13: String has no member shout",
                "s.koine:4:9: no module Nowhere",
                "s.koine:5:27: Binary has no field or member size"),
                problems("s.koine", """
                script S<OS>
                  print Path.jion('a')
                  print 'a'.shout
                  print Nowhere.thing
                  print Binary(name: 'a').size
                  record Binary
                    name: String
                """));
    }

    @Test
    void recordsAreMadeByNamingEveryField() {
        assertEquals(List.of(
                "s.koine:2:18: Binary has no field nmae",
                "s.koine:2:11: Binary needs name",
                "s.koine:3:18: a record is made by naming its fields: Binary(field: …)",
                "s.koine:3:11: Binary needs name"), problems("s.koine", """
                script S<OS>
                  let a = Binary(nmae: 'x')
                  let b = Binary('x')
                  print a.name
                  record Binary
                    name: String
                    source: String?
                """));
    }

    @Test
    void dataIsReadIntoARecordWithAs() {
        assertEquals(List.of(
                "s.koine:2:16: read data into a record with as: … as Record",
                "s.koine:3:11: as reads data, such as Json.read(…), into a record; this is String",
                "s.koine:4:29: data is read into a record, not String",
                "s.koine:5:38: App has no field or member versoin"), problems("s.koine", """
                script S<Windows>
                  let a = Json.read('a.json')
                  let b = 'x' as App
                  let c = Json.read('a') as String
                  print (Json.read('a.json') as App).versoin
                  record App
                    version: String
                """));
    }

    @Test
    void anUntypedFunctionTakesItsTypesFromItsFirstCall() {
        assertEquals(List.of(
                "s.koine:3:15: twice takes String for text, not Number",
                "s.koine:4:9: print takes a String, not Number"), problems("s.koine", """
                script S<OS>
                  print twice('a')
                  print twice(1)
                  print count('a')
                  def twice(text)
                    return {}"{text}{text}"
                  def count(text)
                    return 2
                """));
        assertEquals(List.of("s.koine:2:3: unused is never called, so the types of its parameters are not known; "
                + "declare them"), problems("s.koine", """
                script S<OS>
                  def unused(x)
                    print x
                """));
    }

    @Test
    void aFunctionReturnsOneKindOfThing() {
        assertEquals(List.of(
                "M.koine:5:12: M.name returns String, not Number",
                "M.koine:9:3: M.check declares no result, so its return takes no value",
                "M.koine:12:3: M.must must return Bool"), problems("M.koine", """
                module M

                def name(x: Bool): String
                  if x
                    return 1
                  return 'a'

                def check
                  return 'x'

                def must: Bool
                  return
                """));
    }

    // ---- what goes where ----------------------------------------------------------------------------------------

    @Test
    void aScriptEndsEarlyWithReturnButNotWithAValue() {
        assertEquals(List.of(
                "s.koine:3:5: a script returns no value: return ends it successfully, and fail unsuccessfully",
                "s.koine:5:5: return cannot be used inside a block given to a member, such as within or step, for now"),
                problems("s.koine", """
                script S<OS>
                  if true
                    return 'done'
                  within 'x'
                    return
                  return
                """));
    }

    @Test
    void captureFallbacksAreStrings() {
        assertEquals(List.of("s.koine:2:32: a capture's fallback must be a String, not Number",
                "s.koine:3:37: fail takes a String, not Bool"), problems("s.koine", """
                script S<OS>
                  let a = `git rev-parse` else 0
                  let b = `git rev-parse` else fail true
                  let c = `git rev-parse` else `git log` else 'none'
                """));
    }

    @Test
    void aCommandInsertsStringsOrListsOfThem() {
        assertEquals(List.of("s.koine:4:13: a command takes a String or a [String], not Number; convert it with .text"),
                problems("s.koine", """
                script S<OS>
                  let files = ['a', 'b']
                  {}`rm {files}`
                  {}`sleep {3}`
                """));
    }

    // ---- OSes and tiers -----------------------------------------------------------------------------------------

    @Test
    void everyOSTheFileIsForHasABody() {
        assertEquals(List.of("M.koine:3:1: M.f has no Linux body", "M.koine:3:1: M.f has no Mac body"),
                problems("M.koine", """
                module M

                def f(x: String)
                  native Windows
                    Write-Host $x
                """));
        assertEquals(List.of(), problems("s.koine", """
                script S<Windows>
                  build
                  def build
                    native Windows
                      cmd /c build.cmd
                """));
        assertEquals(List.of("s.koine:3:3: build has no Mac body"), problems("s.koine", """
                script S<Windows, Mac>
                  build
                  def build
                    on Windows
                      `build.cmd`
                """));
    }

    @Test
    void aDefsBodyIsKoineOrBranchesNotBoth() {
        assertEquals(List.of(
                "M.koine:6:3: a def's body is either Koine, or branches by OS with on and native, not both",
                "M.koine:7:3: Fridge is not an OS: Windows, Linux or Mac",
                "M.koine:9:3: M.f has two Linux bodies"), problems("M.koine", """
                module M

                def f
                  native Windows, Linux
                    x
                  print 'a'
                  native Mac, Fridge
                    y
                  on Linux
                    print 'b'
                """).stream().sorted().toList());
    }

    @Test
    void aNativeMemberUsesNoBundledOne() {
        assertEquals(List.of("M.koine:4:16: M.config is native on Linux, Mac, so it cannot use Json.read, which is "
                + "bundled there"), problems("M.koine", """
                module M

                def config: String
                  return (Json.read('c.json') as Config).name

                record Config
                  name: String
                """));
        assertEquals(List.of(), problems("M.koine", """
                module M bundled

                def config: String
                  return (Json.read('c.json') as Config).name

                record Config
                  name: String
                """));
    }

    // ---- members that take a block ------------------------------------------------------------------------------

    private static final String LOCK = """
            module Lock

            def with(path: String, body: Block)
              acquire(path)
              body
              finally
                release(path)

            def acquire(path: String)
              native Windows, Linux, Mac
                x

            def release(path: String)
              native Windows, Linux, Mac
                x
            """;

    @Test
    void aMemberThatTakesABlockIsGivenOne() {
        assertEquals(List.of(), problems("Lock.koine", LOCK));
        assertEquals(List.of(
                "s.koine:4:8: Lock.acquire does not take a block",
                "s.koine:6:8: Lock.with takes 2 arguments, not 1",
                "s.koine:7:22: Lock.with takes a Block for body: a block indented under the call, or a Block parameter "
                        + "passed on"), problems("s.koine", """
                script S<OS>
                  Lock.with('state')
                    print 'locked'
                  Lock.acquire('state')
                    print 'no'
                  Lock.with('state')
                  Lock.with('state', 'x')
                """, Map.of("Lock", LOCK)));
    }

    @Test
    void aBlockIsOnlyRunOrPassedOn() {
        assertEquals(List.of(
                "M.koine:3:1: M.twice takes a block, so it cannot call itself, directly or through other members that "
                        + "take one",
                "M.koine:4:11: a block can only be run, or passed on to a member that takes one"), problems("M.koine", """
                module M

                def twice(body: Block)
                  let b = body
                  twice(body)
                  body
                """).stream().sorted().toList());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static List<String> problems(String source, String text) {
        return problems(source, text, Map.of());
    }

    /** Checks {@code text}, with {@code modules} found before the library's. */
    private static List<String> problems(String source, String text, Map<String, String> modules) {
        Library library = name -> Optional.ofNullable(modules.get(name)).or(() -> LIBRARY.module(name));
        return Checker.check(source, text, library).stream().map(Problem::toString).toList();
    }

    private static String resource(String path) {
        try (InputStream in = CheckerTest.class.getResourceAsStream("/" + path)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
