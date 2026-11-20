# M-05: Architecture tests and the decisions log

Card: `M-05` · Branch: `feature/M-05-arch-tests` · Depends on: M-01

## What we are building, in one paragraph

Two ways of protecting the design. First, tests that read our compiled code and fail the build if a
rule about the structure is broken, for example "`msika-core` must not import Spring". Second, a
short written record (`docs/decisions/`) of the five big choices the design rests on and why we made
them, so nobody has to guess or re-argue them.

## New ideas in this lesson

### Architecture tests (ArchUnit)
- **What it is:** ArchUnit is a library that loads your compiled `.class` files and lets you write
  rules about them as tests. You write rules such as "classes in package A must not depend on classes
  in package B".
- **Why here:** rules in `AGENTS.md` (core imports no Spring, Msika never imports the simulators) are
  easy to break by accident, and a reviewer may not notice an extra `import`. A test notices every
  time.
- **Tiny example:**
  ```java
  noClasses().that().resideInAPackage("zm.msika.core..")
             .should().dependOnClassesThat().resideInAPackage("org.springframework..");
  ```
  (`..` means "this package and everything below it".)

### Vacuous truth: a rule that cannot fail
- **What it is:** "no class in `zm.msika.core` imports Spring" is trivially true if `zm.msika.core` has
  no classes. Today it has none (javac does not even emit a class for `package-info.java` unless it
  has annotations). So the rule would stay green even if it were written wrongly.
- **Why it matters:** a test that can never fail gives false comfort. This is the most important idea
  in the lesson.
- **What we did:** every rule is a constant, and a second test (`RulesCanFailTest`) runs each rule
  against deliberately bad classes (the `fixture` packages in the test sources) and expects a
  violation. If someone mistypes a package name in a rule, its "can fail" test goes red.

### Layers and dependency direction
- **What it is:** a rule about which module may use which.
  ```
  msika-api  -->  msika-core  -->  msika-ledger
  ```
  An arrow means "may depend on". Nothing may point backwards.
- **Why here:** the ledger and core must stay small and free of frameworks, so they can be tested
  without a database or web server and trusted.

### Architecture decision records (ADRs)
- **What it is:** a short document per decision: context, what we decided, and the consequences.
- **Why here:** in six months nobody remembers why money is a `long` or why simulators are separate
  services. The record answers it. If we change our mind we write a new record that supersedes the old
  one, so the history stays.

## Where it sits in the architecture

The tests live in `msika-api` because it is the one module that depends on all the others, so all
their classes are on its test classpath. They check the real, compiled main classes of every Msika
module.

```
ArchitectureTest     real code            -->  must satisfy every rule
RulesCanFailTest     deliberately bad code -->  must violate every rule
```

## Step by step

1. Branched from M-04 and marked the card ◐. Looked up the ArchUnit version (1.5.1).
2. **`ArchitectureRules`**: five rules as constants.
   - `msika-core` and `msika-ledger` import no Spring, JPA/Hibernate, Jackson (both package names it
     has used), `java.net.http` or other HTTP clients.
   - `msika-ledger` does not depend on core or api; core does not depend on api.
   - No Msika code imports `zm.msika.zamsim` or `zm.msika.elevysim`.
   - Each rule says `allowEmptyShould(true)`, which means "no matching classes is fine" for now,
     because the modules are still empty.
3. **`ArchitectureTest`**: loads the main classes of `zm.msika` (tests excluded) and checks each rule.
4. **Bad fixtures and `RulesCanFailTest`**: five tiny bad classes in test sources, each breaking one
   rule, and a test per rule that expects it to fail.
5. **Tried the real thing.** We temporarily gave `msika-core` the Spring dependency and a class using
   `@Component`, ran the build, and watched `ArchitectureTest > coreIsPlainJava()` fail. Then we
   reverted it.
6. **Decisions log**: five records and an index in `docs/decisions/`.

## Files touched

| File | What changed | Why |
|---|---|---|
| `platform/msika-api/build.gradle.kts` | Added ArchUnit (test only) | The library |
| `platform/msika-api/src/test/.../architecture/ArchitectureRules.java` | New | The rules |
| `platform/msika-api/src/test/.../architecture/ArchitectureTest.java` | New | Checks real code |
| `platform/msika-api/src/test/.../architecture/RulesCanFailTest.java` | New | Proves the rules bite |
| `platform/msika-api/src/test/.../*/fixture/*.java` | New, 5 classes | Deliberately bad code, test sources only |
| `docs/decisions/*` | New, 5 records and index | The decisions log |

## How we know it works

| Done item | Proof |
|---|---|
| Adding `import org.springframework...` to a class in `msika-core` fails the build | Tried it: with Spring made available to `msika-core` and a `@Component` class added, `./gradlew :msika-api:test` failed on `ArchitectureTest > coreIsPlainJava()`. Reverted afterwards. |
| The rules do not pass vacuously | `RulesCanFailTest`: five tests, one per rule, each expects a violation from a bad fixture. |
| Decisions log has the five records | `docs/decisions/0001` to `0005`. |

Note: with the current build files, `msika-core` has no Spring on its classpath, so a stray import
there also fails to compile. The ArchUnit test is the second line of defence, for the day someone adds
the dependency "just this once".

## Rebuild it yourself

1. Add ArchUnit to a project and write one rule: classes in `a..` must not use `b..`.
2. Run it. Then add a class that breaks it and watch the failure message.
3. Notice what happens when the package is empty. Add `allowEmptyShould(true)` and see why that is
   dangerous.
4. Write a "can fail" test with a deliberately bad class to prove your rule works.
5. Write a decision record for a choice in your own project.

## Mistakes to avoid

- **Trusting a green rule you have never seen fail.** Always make it fail once on purpose.
- **Putting the bad fixture classes in main sources.** They must live in test sources only.
  `ArchitectureTest` also excludes tests when it imports the real code.
- **Editing an accepted decision.** Supersede it with a new record instead.
