#!/usr/bin/env groovy

import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

Map runCommand(List<String> command, Path directory) {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
    String output = process.inputStream.getText(StandardCharsets.UTF_8.name())
    int exitCode = process.waitFor()
    [exitCode: exitCode, output: output]
}

void copyTree(Path source, Path destination) {
    Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
        @Override
        FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
            Files.createDirectories(destination.resolve(source.relativize(directory)))
            FileVisitResult.CONTINUE
        }

        @Override
        FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING)
            FileVisitResult.CONTINUE
        }
    })
}

String sha256(Path path) {
    MessageDigest digest = MessageDigest.getInstance('SHA-256')
    digest.digest(Files.readAllBytes(path)).collect { String.format('%02x', it) }.join()
}

List<Map> readJsonLines(Path path) {
    List<Map> rows = []
    path.toFile().eachLine(StandardCharsets.UTF_8.name()) { String line ->
        if (line.trim()) {
            rows << new JsonSlurper().parseText(line) as Map
        }
    }
    rows
}

boolean supportsPosix(Path path) {
    Files.getFileStore(path).supportsFileAttributeView('posix')
}

void assertSecureTree(Path outputRoot, Path bundle) {
    if (!supportsPosix(bundle)) {
        return
    }
    assert PosixFilePermissions.toString(Files.getPosixFilePermissions(outputRoot)) == 'rwx------'
    Files.walk(bundle).withCloseable { stream ->
        stream.forEach { Path path ->
            if (Files.isDirectory(path)) {
                assert PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) == 'rwx------': path
            } else if (Files.isRegularFile(path)) {
                assert PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) == 'rw-------': path
            }
        }
    }
}

Path cursorRoot = Paths.get(System.getProperty('user.dir')).toAbsolutePath().normalize()
Path exporter = cursorRoot.resolve('cursor-session-exporter.groovy')
assert !Files.exists(cursorRoot.resolve('session-exporter.groovy'))
Path fixtureRoot = cursorRoot.resolve('tests/fixtures')
Path temporary = Files.createTempDirectory('session-exporter-test-')
Path project = temporary.resolve('cursor-project')
Path workspace = temporary.resolve('workspace')
Path output = temporary.resolve('output')
copyTree(fixtureRoot.resolve('cursor-project'), project)
copyTree(fixtureRoot.resolve('workspace'), workspace)
if (supportsPosix(project)) {
    Files.setPosixFilePermissions(project.resolve('agent-tools/result.txt'), PosixFilePermissions.fromString('rw-r--r--'))
    Files.setPosixFilePermissions(project.resolve('terminals/1.txt'), PosixFilePermissions.fromString('rw-r--r--'))
}

Path rootTranscript = project.resolve('agent-transcripts/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa.jsonl')
String transcriptText = Files.readString(rootTranscript, StandardCharsets.UTF_8)
    .replace('__WORKSPACE__', workspace.toString())
    .replace('__AGENT_TOOL_PATH__', project.resolve('agent-tools/result.txt').toString())
Files.writeString(rootTranscript, transcriptText, StandardCharsets.UTF_8)
Files.list(project.resolve('terminals')).withCloseable { stream ->
    stream.forEach { Path path ->
        Files.writeString(path, Files.readString(path, StandardCharsets.UTF_8).replace('__WORKSPACE__', workspace.toString()), StandardCharsets.UTF_8)
    }
}

List<String> base = [
    'groovy',
    exporter.toString(),
    'aaaaaaaa',
    '--transcript-root', project.resolve('agent-transcripts').toString(),
    '--terminal-root', project.resolve('terminals').toString(),
    '--agent-tool-root', project.resolve('agent-tools').toString(),
    '--workspace', workspace.toString(),
    '--output-dir', output.toString()
]

Map first = runCommand(base, cursorRoot)
assert first.exitCode == 0: first.output
Path bundle = output.resolve('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
assert Files.isDirectory(bundle)
assert Files.isRegularFile(bundle.resolve('aaaaaaaa-manifest.json'))
assert Files.isRegularFile(bundle.resolve('session/aaaaaaaa-session.jsonl'))
assert Files.isRegularFile(bundle.resolve('references/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb/session/bbbbbbbb-session.jsonl'))
assertSecureTree(output, bundle)

Map graph = new JsonSlurper().parse(bundle.resolve('aaaaaaaa-reference-graph.json').toFile()) as Map
assert (graph.sessions as List).contains('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
assert (graph.edges as List).any { it.from == 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' && it.to == 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' }
assert (graph.edges as List).any { it.from == 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' && it.to == 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' }

List<Map> commands = readJsonLines(bundle.resolve('commands/aaaaaaaa-commands.jsonl'))
assert commands.size() == 4
assert commands.find { it.command == 'echo ok' }.correlation == 'exact'
assert commands.find { it.command == 'echo inferred' }.correlation == 'inferred'
assert commands.find { it.command == 'false' }.exitCode == 1
assert commands.find { it.command == 'echo missing' }.correlation == 'unmatched'

Map scriptIndex = new JsonSlurper().parse(bundle.resolve('scripts/aaaaaaaa-script-index.json').toFile()) as Map
assert scriptIndex.operations == 2
assert scriptIndex.revisions == 2
assert (scriptIndex.snapshots as List).size() == 1
assert Files.isRegularFile(bundle.resolve((scriptIndex.snapshots as List).first().exportPath.toString()))

Map artifactIndex = new JsonSlurper().parse(bundle.resolve('artifacts/aaaaaaaa-artifacts.json').toFile()) as Map
assert (artifactIndex.artifacts as List).any { it.sourcePath.toString().endsWith('/agent-tools/result.txt') }

Map validation = new JsonSlurper().parse(bundle.resolve('integrity/aaaaaaaa-validation.json').toFile()) as Map
assert validation.valid
Map manifest = new JsonSlurper().parse(bundle.resolve('aaaaaaaa-manifest.json').toFile()) as Map
assert manifest.exporter == 'cursor-session-exporter.groovy'
assert manifest.security.directoryMode == '0700'
assert manifest.security.fileMode == '0600'
Path stableTimeline = bundle.resolve('session/aaaaaaaa-session.jsonl')
String firstTimelineHash = sha256(stableTimeline)

Map second = runCommand(base, cursorRoot)
assert second.exitCode == 0: second.output
assert second.output.contains('reused artifacts:')
assert sha256(stableTimeline) == firstTimelineHash

Map validateOnly = runCommand(base + ['--validate-only'], cursorRoot)
assert validateOnly.exitCode == 0: validateOnly.output
if (supportsPosix(bundle)) {
    Path manifestPath = bundle.resolve('aaaaaaaa-manifest.json')
    Files.setPosixFilePermissions(manifestPath, PosixFilePermissions.fromString('rw-r--r--'))
    Map insecureValidation = runCommand(base + ['--validate-only'], cursorRoot)
    assert insecureValidation.exitCode == 8
    assert PosixFilePermissions.toString(Files.getPosixFilePermissions(manifestPath)) == 'rw-r--r--'
    Files.setPosixFilePermissions(manifestPath, PosixFilePermissions.fromString('rw-------'))
}

Map fullUuid = runCommand(base.with { List<String> values ->
    List<String> copy = new ArrayList<>(values)
    copy[2] = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
    copy
}, cursorRoot)
assert fullUuid.exitCode == 0: fullUuid.output

Map ambiguous = runCommand([
    'groovy', exporter.toString(), 'dddddddd',
    '--transcript-root', project.resolve('agent-transcripts').toString(),
    '--output-dir', output.toString()
], cursorRoot)
assert ambiguous.exitCode == 5

Map missing = runCommand([
    'groovy', exporter.toString(), 'ffffffff-ffff-4fff-8fff-ffffffffffff',
    '--transcript-root', project.resolve('agent-transcripts').toString(),
    '--output-dir', output.toString()
], cursorRoot)
assert missing.exitCode == 4

Map malformedIdentifier = runCommand(['groovy', exporter.toString(), 'not-an-id'], cursorRoot)
assert malformedIdentifier.exitCode == 3

Map malformedJsonl = runCommand([
    'groovy', exporter.toString(), 'eeeeeeee',
    '--transcript-root', project.resolve('agent-transcripts').toString(),
    '--output-dir', output.toString()
], cursorRoot)
assert malformedJsonl.exitCode == 0: malformedJsonl.output
Map malformedIssues = new JsonSlurper().parse(output.resolve('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee/integrity/eeeeeeee-missing-items.json').toFile()) as Map
assert (malformedIssues.items as List).any { it.type == 'transcript_parse_error' }

String originalTranscript = Files.readString(rootTranscript, StandardCharsets.UTF_8)
String preservedHash = sha256(stableTimeline)
Files.writeString(rootTranscript, 'invalid-only\n', StandardCharsets.UTF_8)
Map failedReplacement = runCommand(base, cursorRoot)
assert failedReplacement.exitCode == 6
assert Files.isRegularFile(stableTimeline)
assert sha256(stableTimeline) == preservedHash
Files.writeString(rootTranscript, originalTranscript, StandardCharsets.UTF_8)

println('cursor-session-exporter tests passed')
