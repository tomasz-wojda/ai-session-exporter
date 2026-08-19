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
    List<String> effectiveCommand = new ArrayList<>(command)
    if (!effectiveCommand.isEmpty() && effectiveCommand.first() == 'groovy') {
        effectiveCommand.add(1, "-Duser.home=${System.getProperty('session.exporter.test.home')}".toString())
    }
    Process process = new ProcessBuilder(effectiveCommand).directory(directory.toFile()).redirectErrorStream(true).start()
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
Path testHome = temporary.resolve('home')
System.setProperty('session.exporter.test.home', testHome.toString())
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

Path configHome = testHome.resolve('.cursor-session-exporter')
Path configPath = configHome.resolve('config.json')
Map emptyConfig = runCommand(['groovy', exporter.toString(), 'config'], cursorRoot)
assert emptyConfig.exitCode == 0: emptyConfig.output
assert new JsonSlurper().parseText(emptyConfig.output) == [version: 1]
assert !Files.exists(configPath)

Path configuredOutput = temporary.resolve('configured-output')
Map saveConfig = runCommand([
    'groovy', exporter.toString(), 'config',
    '--output-dir', configuredOutput.toString(),
    '--transcript-root', project.resolve('agent-transcripts').toString(),
    '--terminal-root', project.resolve('terminals').toString(),
    '--agent-tool-root', project.resolve('agent-tools').toString(),
    '--workspace', workspace.toString(),
    '--reference-scope', 'recursive'
], cursorRoot)
assert saveConfig.exitCode == 0: saveConfig.output
Map storedConfig = new JsonSlurper().parse(configPath.toFile()) as Map
assert storedConfig.version == 1
assert storedConfig.outputDir == configuredOutput.toString()
assert storedConfig.referenceScope == 'recursive'
assert storedConfig.workspace == workspace.toString()
if (supportsPosix(configHome)) {
    assert PosixFilePermissions.toString(Files.getPosixFilePermissions(configHome)) == 'rwx------'
    assert PosixFilePermissions.toString(Files.getPosixFilePermissions(configPath)) == 'rw-------'
}

Map configuredExport = runCommand(['groovy', exporter.toString(), 'aaaaaaaa'], cursorRoot)
assert configuredExport.exitCode == 0: configuredExport.output
Path configuredBundle = configuredOutput.resolve('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
Map configuredGraph = new JsonSlurper().parse(configuredBundle.resolve('aaaaaaaa-reference-graph.json').toFile()) as Map
assert configuredGraph.referenceScope == 'recursive'
assert (configuredGraph.exportedSessions as List).size() == 4

Path overrideOutput = temporary.resolve('override-output')
Map overrideExport = runCommand([
    'groovy', exporter.toString(), 'aaaaaaaa',
    '--output-dir', overrideOutput.toString(),
    '--reference-scope', 'none'
], cursorRoot)
assert overrideExport.exitCode == 0: overrideExport.output
Map overrideGraph = new JsonSlurper().parse(overrideOutput.resolve('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/aaaaaaaa-reference-graph.json').toFile()) as Map
assert overrideGraph.referenceScope == 'none'
assert overrideGraph.exportedSessions == ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa']

Map configuredValidation = runCommand(['groovy', exporter.toString(), 'aaaaaaaa', '--validate-only'], cursorRoot)
assert configuredValidation.exitCode == 0: configuredValidation.output

Map partialConfig = runCommand(['groovy', exporter.toString(), 'config', '--reference-scope', 'relevant'], cursorRoot)
assert partialConfig.exitCode == 0: partialConfig.output
storedConfig = new JsonSlurper().parse(configPath.toFile()) as Map
assert storedConfig.outputDir == configuredOutput.toString()
assert storedConfig.referenceScope == 'relevant'
String validConfigHash = sha256(configPath)
Map invalidConfigUpdate = runCommand(['groovy', exporter.toString(), 'config', '--reference-scope', 'invalid'], cursorRoot)
assert invalidConfigUpdate.exitCode == 2
assert sha256(configPath) == validConfigHash

Map unsetConfig = runCommand([
    'groovy', exporter.toString(), 'config',
    '--unset', 'outputDir',
    '--unset', 'transcriptRoot',
    '--unset', 'terminalRoot',
    '--unset', 'agentToolRoot',
    '--unset', 'workspace',
    '--unset', 'referenceScope'
], cursorRoot)
assert unsetConfig.exitCode == 0: unsetConfig.output
assert new JsonSlurper().parse(configPath.toFile()) == [version: 1]

Files.writeString(configPath, '{invalid\n', StandardCharsets.UTF_8)
if (supportsPosix(configPath)) {
    Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString('rw-------'))
}
Map malformedConfig = runCommand(['groovy', exporter.toString(), 'config'], cursorRoot)
assert malformedConfig.exitCode == 2
Files.writeString(configPath, '{\n    "unknown": true,\n    "version": 1\n}\n', StandardCharsets.UTF_8)
if (supportsPosix(configPath)) {
    Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString('rw-------'))
}
Map unknownConfig = runCommand(['groovy', exporter.toString(), 'config'], cursorRoot)
assert unknownConfig.exitCode == 2
Files.writeString(configPath, '{\n    "version": 1\n}\n', StandardCharsets.UTF_8)
if (supportsPosix(configPath)) {
    Files.setPosixFilePermissions(configPath, PosixFilePermissions.fromString('rw-------'))
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
assert Files.isRegularFile(bundle.resolve('references/cccccccc-cccc-4ccc-8ccc-cccccccccccc/session/cccccccc-session.jsonl'))
assert !Files.exists(bundle.resolve('references/99999999-9999-4999-8999-999999999999'))
assert Files.isRegularFile(bundle.resolve('aaaaaaaa-reference-evidence.jsonl'))
assert Files.isRegularFile(bundle.resolve('aaaaaaaa-reference-summary.json'))
assert Files.isRegularFile(bundle.resolve('aaaaaaaa-reference-index.json'))
assert Files.isRegularFile(bundle.resolve('aaaaaaaa-relevant-reference-graph.json'))
assertSecureTree(output, bundle)

Map graph = new JsonSlurper().parse(bundle.resolve('aaaaaaaa-reference-graph.json').toFile()) as Map
assert graph.schemaVersion == 2
assert graph.referenceModelVersion == 1
assert graph.referenceScope == 'relevant'
assert (graph.sessions as List).contains('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb')
assert (graph.sessions as List).contains('cccccccc-cccc-4ccc-8ccc-cccccccccccc')
assert (graph.sessions as List).contains('99999999-9999-4999-8999-999999999999')
Map directEdge = (graph.edges as List).find { it.from == 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' && it.to == 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' } as Map
assert directEdge.confidence == 'high'
assert directEdge.strongestEvidenceType == 'explicit_session_link'
assert directEdge.evidenceCount == 5
assert (directEdge.evidenceTypes as List).containsAll(['explicit_session_link', 'message', 'tool_input', 'shell_command', 'file_content'])
assert (graph.edges as List).any { it.from == 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' && it.to == 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' }
Map indirectEdge = (graph.edges as List).find { it.from == 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' && it.to == 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' } as Map
assert indirectEdge.evidenceCount == 2
assert indirectEdge.strongestEvidenceType == 'summary'
assert indirectEdge.identifierForm == 'full_uuid'
assert (graph.cycleGroups as List).any { (it as List).containsAll(['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb']) }
Map directNode = (graph.nodes as List).find { it.sessionId == 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' } as Map
assert directNode.baseRelationship == 'direct'
assert directNode.relationship == 'cyclic'
assert directNode.depth == 1
assert directNode.relevance == 'primary'
assert directNode.shortestVector == ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb']
Map indirectNode = (graph.nodes as List).find { it.sessionId == 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' } as Map
assert indirectNode.baseRelationship == 'indirect'
assert indirectNode.depth == 2
assert indirectNode.confidence == 'medium'
assert indirectNode.relevance == 'supporting'
Map incidentalNode = (graph.nodes as List).find { it.sessionId == '99999999-9999-4999-8999-999999999999' } as Map
assert incidentalNode.baseRelationship == 'direct'
assert incidentalNode.confidence == 'low'
assert incidentalNode.relevance == 'incidental'
assert (graph.unresolved as List).any { it.identifier == 'dddddddd' && it.reason == 'ambiguous_reference' }
List<Map> referenceEvidence = readJsonLines(bundle.resolve('aaaaaaaa-reference-evidence.jsonl'))
assert referenceEvidence.count { it.from == 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' && it.to == 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' } == 5
assert referenceEvidence.any { it.to == '99999999-9999-4999-8999-999999999999' && it.evidenceType == 'transcript_path' }
assert referenceEvidence.any { it.to == 'cccccccc-cccc-4ccc-8ccc-cccccccccccc' && it.evidenceType == 'summary' }
Map referenceIndex = new JsonSlurper().parse(bundle.resolve('aaaaaaaa-reference-index.json').toFile()) as Map
assert (referenceIndex.direct as List).any { it.vectorText == 'aaaaaaaa -> bbbbbbbb' }
assert (referenceIndex.indirect as List).any { it.vectorText == 'aaaaaaaa -> bbbbbbbb -> cccccccc' }

List<Map> commands = readJsonLines(bundle.resolve('commands/aaaaaaaa-commands.jsonl'))
assert commands.size() == 4
assert commands.find { it.command == 'echo ok' }.correlation == 'exact'
assert commands.find { it.command == 'echo inferred' }.correlation == 'inferred'
assert commands.find { it.command == 'false' }.exitCode == 1
assert commands.find { it.command == 'echo missing bbbbbbbb' }.correlation == 'unmatched'

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
assert manifest.schemaVersion == 2
assert manifest.referenceModelVersion == 1
assert manifest.referenceScope == 'relevant'
assert manifest.security.directoryMode == '0700'
assert manifest.security.fileMode == '0600'
Map restoreContext = new JsonSlurper().parse(bundle.resolve('aaaaaaaa-restore-context.json').toFile()) as Map
assert restoreContext.schemaVersion == 2
assert restoreContext.references.scope == 'relevant'
assert (restoreContext.references.relevant as List).size() == 2
Path stableTimeline = bundle.resolve('session/aaaaaaaa-session.jsonl')
String firstTimelineHash = sha256(stableTimeline)
Path stableGraph = bundle.resolve('aaaaaaaa-reference-graph.json')
String firstGraphHash = sha256(stableGraph)

Map second = runCommand(base, cursorRoot)
assert second.exitCode == 0: second.output
assert second.output.contains('reused artifacts:')
assert sha256(stableTimeline) == firstTimelineHash
assert sha256(stableGraph) == firstGraphHash

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

['recursive': ['bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '99999999-9999-4999-8999-999999999999', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'],
 'direct': ['bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '99999999-9999-4999-8999-999999999999'],
 'relevant': ['bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'],
 'none': []].each { String scope, List<String> expectedReferences ->
    Path scopeOutput = temporary.resolve("output-${scope}")
    List<String> scopedCommand = new ArrayList<>(base)
    scopedCommand[scopedCommand.indexOf(output.toString())] = scopeOutput.toString()
    scopedCommand.addAll(['--reference-scope', scope])
    Map scoped = runCommand(scopedCommand, cursorRoot)
    assert scoped.exitCode == 0: scoped.output
    Path scopedBundle = scopeOutput.resolve('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    Map scopedGraph = new JsonSlurper().parse(scopedBundle.resolve('aaaaaaaa-reference-graph.json').toFile()) as Map
    assert scopedGraph.referenceScope == scope
    assert scopedGraph.exportedSessions == ['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'] + expectedReferences
    expectedReferences.each { String sessionId ->
        assert Files.isDirectory(scopedBundle.resolve('references').resolve(sessionId))
    }
    ((scopedGraph.omittedSessions ?: []) as List).each { String sessionId ->
        assert !Files.exists(scopedBundle.resolve('references').resolve(sessionId))
    }
    assertSecureTree(scopeOutput, scopedBundle)
}

Map invalidScope = runCommand(base + ['--reference-scope', 'invalid'], cursorRoot)
assert invalidScope.exitCode == 2

println('cursor-session-exporter tests passed')
