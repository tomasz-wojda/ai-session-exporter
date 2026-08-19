#!/usr/bin/env groovy

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.util.regex.Pattern

class ExportFailure extends RuntimeException {
    int exitCode

    ExportFailure(int exitCode, String message) {
        super(message)
        this.exitCode = exitCode
    }
}

class SessionExporter {
    static final Pattern UUID_PATTERN = Pattern.compile('(?i)(?<![0-9a-f])([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?![0-9a-f])')
    static final Pattern PREFIX_PATTERN = Pattern.compile('(?i)(?<![0-9a-f])([0-9a-f]{8})(?![0-9a-f-])')
    static final Pattern URL_PATTERN = Pattern.compile('https?://[^\\s\"\'<>]+')
    static final Pattern ABSOLUTE_PATH_PATTERN = Pattern.compile('(?:^|[\\s\"\'(])(/(?:Users|tmp|var|opt|etc|home)/[^\\s\"\'<>),]+)')
    static final Pattern HOST_PATTERN = Pattern.compile('(?i)\\b(?:[a-z0-9][a-z0-9-]*\\.)+(?:com|net|org|io|pl|local|internal|iti)\\b')
    static final Pattern ID_PATTERN = Pattern.compile('\\b\\d{5,}\\b')
    static final Set<String> FILE_TOOLS = ['write', 'edit', 'applypatch', 'editnotebook', 'delete'] as Set
    static final Set<String> SHELL_TOOLS = ['shell', 'awaitshell', 'await'] as Set
    static final int MAX_SNAPSHOT_BYTES = 25 * 1024 * 1024

    Map options
    Path scriptDir
    Path outputRoot
    Map<String, Path> transcriptIndex = new TreeMap<>()
    Map<String, List<Map>> parsedCache = [:]
    List<Map> terminalRecords = []
    Set<String> usedTerminalPaths = [] as Set
    List<Map> completeness = []
    List<Map> relationshipEdges = []
    int reusedArtifacts = 0

    SessionExporter(Map options, Path scriptDir) {
        this.options = options
        this.scriptDir = scriptDir
        this.outputRoot = options.containsKey('outputDir') ? options.get('outputDir') as Path : scriptDir.resolve('sessions-export')
    }

    static int run(String[] args, Path scriptDir) {
        try {
            Map options = parseArguments(args)
            SessionExporter exporter = new SessionExporter(options, scriptDir)
            exporter.execute()
            return 0
        } catch (ExportFailure failure) {
            System.err.println("session-exporter: ${failure.message}")
            return failure.exitCode
        } catch (Throwable failure) {
            System.err.println("session-exporter: ${failure.class.simpleName}: ${failure.message}")
            return 9
        }
    }

    static Map parseArguments(String[] args) {
        if (!args || args[0] in ['--help', '-h']) {
            throw new ExportFailure(2, 'usage: groovy session-exporter.groovy <session_id> [--output-dir PATH] [--transcript-root PATH] [--terminal-root PATH] [--agent-tool-root PATH] [--workspace PATH] [--validate-only]')
        }
        Map options = [sessionId: args[0], validateOnly: false]
        Set<String> pathOptions = ['--output-dir', '--transcript-root', '--terminal-root', '--agent-tool-root', '--workspace'] as Set
        int index = 1
        while (index < args.length) {
            String argument = args[index]
            if (argument == '--validate-only') {
                options.validateOnly = true
                index++
                continue
            }
            if (!pathOptions.contains(argument) || index + 1 >= args.length) {
                throw new ExportFailure(2, "invalid argument: ${argument}")
            }
            String key = [
                '--output-dir': 'outputDir',
                '--transcript-root': 'transcriptRoot',
                '--terminal-root': 'terminalRoot',
                '--agent-tool-root': 'agentToolRoot',
                '--workspace': 'workspace'
            ][argument]
            options[key] = Paths.get(args[index + 1]).toAbsolutePath().normalize()
            index += 2
        }
        String sessionId = options.sessionId as String
        if (!(sessionId ==~ /(?i)([0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/)) {
            throw new ExportFailure(3, "malformed session identifier: ${sessionId}")
        }
        options
    }

    void execute() {
        indexTranscripts()
        String rootSession = resolveSessionId(options.sessionId as String)
        Path destination = outputRoot.resolve(rootSession)
        if (options.validateOnly) {
            Map result = validateBundle(destination)
            if (!result.valid) {
                throw new ExportFailure(8, "validation failed: ${(result.errors as List).join('; ')}")
            }
            println("Validated ${destination}")
            return
        }
        Path mainTranscript = transcriptIndex[rootSession]
        configureEvidenceRoots(mainTranscript)
        terminalRecords = loadTerminalRecords(options.terminalRoot as Path)
        Map graph = discoverReferences(rootSession)
        Path staging = outputRoot.resolve(".${rootSession}.staging-${UUID.randomUUID()}")
        Path previous = outputRoot.resolve(".${rootSession}.previous-${UUID.randomUUID()}")
        deleteRecursively(staging)
        Files.createDirectories(staging)
        try {
            Map rootResult = exportOneSession(rootSession, staging, false, destination)
            Path referenceRoot = staging.resolve('references')
            ((graph.sessions as List<String>).findAll { it != rootSession }).each { String referenceId ->
                exportOneSession(referenceId, referenceRoot.resolve(referenceId), true, destination.resolve('references').resolve(referenceId))
            }
            if (Files.exists(referenceRoot) && isDirectoryEmpty(referenceRoot)) {
                Files.delete(referenceRoot)
            }
            writeRootRelationshipFiles(staging, rootSession, graph, rootResult)
            writeIntegrity(staging)
            Map validation = validateBundle(staging)
            writeJson(staging.resolve('integrity').resolve("${rootSession.take(8)}-validation.json"), validation)
            if (!validation.valid) {
                throw new ExportFailure(8, "validation failed: ${(validation.errors as List).join('; ')}")
            }
            Files.createDirectories(outputRoot)
            boolean hadDestination = Files.exists(destination)
            if (hadDestination) {
                movePath(destination, previous)
            }
            try {
                movePath(staging, destination)
                deleteRecursively(previous)
            } catch (Throwable moveFailure) {
                if (hadDestination && Files.exists(previous) && !Files.exists(destination)) {
                    movePath(previous, destination)
                }
                throw moveFailure
            }
            println("Exported ${rootSession} to ${destination}")
            println("Sessions: ${(graph.sessions as List).size()}, references: ${(graph.sessions as List).size() - 1}, reused artifacts: ${reusedArtifacts}")
        } catch (ExportFailure failure) {
            deleteRecursively(staging)
            throw failure
        } catch (Throwable failure) {
            deleteRecursively(staging)
            throw new ExportFailure(7, "export failed: ${failure.message}")
        }
    }

    void indexTranscripts() {
        Path root = options.transcriptRoot as Path
        if (root == null) {
            root = Paths.get(System.getProperty('user.home'), '.cursor', 'projects')
        }
        if (!Files.isDirectory(root)) {
            throw new ExportFailure(4, "transcript root not found: ${root}")
        }
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.jsonl') }.forEach { Path path ->
                String fileName = path.fileName.toString()
                String id = fileName.substring(0, fileName.length() - 6).toLowerCase()
                if (id ==~ /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/ && path.parent?.fileName?.toString()?.equalsIgnoreCase(id)) {
                    transcriptIndex[id] = path.toAbsolutePath().normalize()
                }
            }
        }
        if (transcriptIndex.isEmpty()) {
            throw new ExportFailure(4, "no Cursor transcripts found under ${root}")
        }
    }

    String resolveSessionId(String supplied) {
        String normalized = supplied.toLowerCase()
        if (normalized.length() == 36) {
            if (!transcriptIndex.containsKey(normalized)) {
                throw new ExportFailure(4, "session not found: ${supplied}")
            }
            return normalized
        }
        List<String> matches = transcriptIndex.keySet().findAll { it.startsWith(normalized) }.toList()
        if (matches.isEmpty()) {
            throw new ExportFailure(4, "session prefix not found: ${supplied}")
        }
        if (matches.size() > 1) {
            throw new ExportFailure(5, "ambiguous session prefix ${supplied}: ${matches.join(', ')}")
        }
        matches.first()
    }

    void configureEvidenceRoots(Path transcriptPath) {
        Path projectRoot = transcriptPath.parent?.parent?.parent
        if (options.terminalRoot == null && projectRoot != null) {
            options.terminalRoot = projectRoot.resolve('terminals')
        }
        if (options.agentToolRoot == null && projectRoot != null) {
            options.agentToolRoot = projectRoot.resolve('agent-tools')
        }
        if (options.workspace == null) {
            options.workspace = inferWorkspace(options.terminalRoot as Path) ?: Paths.get(System.getProperty('user.dir')).toAbsolutePath().normalize()
        }
    }

    Path inferWorkspace(Path terminalRoot) {
        if (terminalRoot == null || !Files.isDirectory(terminalRoot)) {
            return null
        }
        Map<String, Integer> counts = [:].withDefault { 0 }
        Files.list(terminalRoot).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { Path path ->
                String text = readText(path)
                def matcher = (text =~ /(?m)^cwd:\s+"?([^"\n]+)"?\s*$/)
                if (matcher.find()) {
                    counts[matcher.group(1)] = counts[matcher.group(1)] + 1
                }
            }
        }
        if (counts.isEmpty()) {
            return null
        }
        Paths.get(counts.entrySet().max { it.value }.key).toAbsolutePath().normalize()
    }

    List<Map> parseTranscript(String sessionId) {
        if (parsedCache.containsKey(sessionId)) {
            return parsedCache[sessionId]
        }
        Path path = transcriptIndex[sessionId]
        List<Map> records = []
        int lineNumber = 0
        path.toFile().withReader(StandardCharsets.UTF_8.name()) { reader ->
            String line
            while ((line = reader.readLine()) != null) {
                lineNumber++
                if (!line.trim()) {
                    continue
                }
                try {
                    Object parsed = new JsonSlurper().parseText(line)
                    if (!(parsed instanceof Map)) {
                        completeness << issue(sessionId, 'transcript_non_object', path.toString(), lineNumber)
                        continue
                    }
                    records << [sessionId: sessionId, lineNumber: lineNumber, sourcePath: path.toString(), raw: parsed]
                } catch (Throwable failure) {
                    completeness << issue(sessionId, 'transcript_parse_error', path.toString(), lineNumber, failure.message)
                }
            }
        }
        if (records.isEmpty()) {
            throw new ExportFailure(6, "no parseable records in ${path}")
        }
        parsedCache[sessionId] = records
        records
    }

    Map discoverReferences(String rootSession) {
        List<String> queue = [rootSession]
        Set<String> visited = new LinkedHashSet<>()
        List<Map> unresolved = []
        while (!queue.isEmpty()) {
            String current = queue.remove(0)
            if (!visited.add(current)) {
                continue
            }
            Set<String> discovered = discoverDirectReferences(current, unresolved)
            discovered.each { String reference ->
                relationshipEdges << [from: current, to: reference, type: 'referenced']
                if (!visited.contains(reference)) {
                    queue << reference
                }
            }
        }
        [
            root: rootSession,
            sessions: visited.toList(),
            edges: relationshipEdges.sort { a, b -> "${a.from}:${a.to}" <=> "${b.from}:${b.to}" },
            unresolved: unresolved
        ]
    }

    Set<String> discoverDirectReferences(String sessionId, List<Map> unresolved) {
        Set<String> references = new TreeSet<>()
        parseTranscript(sessionId).each { Map record ->
            String text = canonicalJson(record.raw)
            def uuidMatcher = UUID_PATTERN.matcher(text)
            while (uuidMatcher.find()) {
                String candidate = uuidMatcher.group(1).toLowerCase()
                if (candidate != sessionId && transcriptIndex.containsKey(candidate)) {
                    references << candidate
                }
            }
            def prefixMatcher = PREFIX_PATTERN.matcher(text)
            while (prefixMatcher.find()) {
                String prefix = prefixMatcher.group(1).toLowerCase()
                List<String> matches = transcriptIndex.keySet().findAll { it.startsWith(prefix) }.toList()
                if (matches.size() == 1 && matches.first() != sessionId) {
                    references << matches.first()
                } else if (matches.size() > 1) {
                    unresolved << [sessionId: sessionId, prefix: prefix, reason: 'ambiguous_reference']
                }
            }
        }
        references
    }

    Map exportOneSession(String sessionId, Path destination, boolean referenced, Path existingDestination) {
        Files.createDirectories(destination)
        String prefix = sessionId.take(8)
        List<Map> records = parseTranscript(sessionId)
        Map normalized = normalizeEvents(sessionId, records)
        Path sessionDir = destination.resolve('session')
        Files.createDirectories(sessionDir)
        writeJsonLines(sessionDir.resolve("${prefix}-session.jsonl"), normalized.timeline as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-queries.jsonl", normalized.queries as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-responses.jsonl", normalized.responses as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-context.jsonl", normalized.context as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-tool-calls.jsonl", normalized.toolCalls as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-tool-results.jsonl", normalized.toolResults as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-summaries.jsonl", normalized.summaries as List)
        Files.copy(transcriptIndex[sessionId], sessionDir.resolve("${prefix}-raw-transcript.jsonl"), StandardCopyOption.REPLACE_EXISTING)

        Map scripts = exportScripts(sessionId, normalized.toolCalls as List, destination, existingDestination)
        Map commands = exportCommands(sessionId, normalized.toolCalls as List, destination, existingDestination)
        Map artifacts = exportReferencedArtifacts(sessionId, records, destination, existingDestination)
        Map workspace = exportWorkspace(sessionId, normalized, destination)
        Map searchIndex = buildSearchIndex(normalized.timeline as List)
        writeJson(destination.resolve("${prefix}-search-index.json"), searchIndex)

        List<Map> sessionIssues = completeness.findAll { it.sessionId == sessionId }
        Map summary = [
            sessionId: sessionId,
            referenced: referenced,
            transcriptRecords: records.size(),
            events: (normalized.timeline as List).size(),
            queries: (normalized.queries as List).size(),
            responses: (normalized.responses as List).size(),
            toolCalls: (normalized.toolCalls as List).size(),
            toolResults: (normalized.toolResults as List).size(),
            fileOperations: scripts.operationCount,
            commands: commands.commandCount,
            matchedCommands: commands.matchedCount,
            artifacts: artifacts.artifactCount,
            completenessIssues: sessionIssues.size()
        ]
        writeJson(destination.resolve("${prefix}-summary.json"), summary)
        Map checkpoint = buildCheckpoint(sessionId, normalized.timeline as List, sessionIssues)
        writeJson(destination.resolve("${prefix}-checkpoint.json"), checkpoint)
        Map restore = buildRestoreContext(sessionId, normalized.timeline as List, checkpoint, workspace, scripts)
        writeJson(destination.resolve("${prefix}-restore-context.json"), restore)
        Map manifest = [
            schemaVersion: 1,
            exporter: 'session-exporter.groovy',
            sessionId: sessionId,
            prefix: prefix,
            referenced: referenced,
            sourceTranscript: transcriptIndex[sessionId].toString(),
            exportedAt: Instant.now().toString(),
            counts: summary,
            paths: listRelativeFiles(destination)
        ]
        writeJson(destination.resolve("${prefix}-manifest.json"), manifest)
        [summary: summary, checkpoint: checkpoint, restore: restore]
    }

    Map normalizeEvents(String sessionId, List<Map> records) {
        List<Map> timeline = []
        List<Map> queries = []
        List<Map> responses = []
        List<Map> context = []
        List<Map> toolCalls = []
        List<Map> toolResults = []
        List<Map> summaries = []
        String previousEventId = null
        records.each { Map record ->
            Map raw = record.raw as Map
            String role = raw.role?.toString() ?: 'unknown'
            Object message = raw.message
            List content = message instanceof Map && message.content instanceof List ? message.content as List : []
            if (content.isEmpty()) {
                content = [[type: 'record', value: raw]]
            }
            content.eachWithIndex { Object item, int contentIndex ->
                Map contentItem = item instanceof Map ? item as Map : [type: 'value', value: item]
                String sourceType = contentItem.type?.toString() ?: 'unknown'
                String eventType
                if (sourceType == 'tool_use') {
                    eventType = 'tool_call'
                } else if (sourceType == 'tool_result') {
                    eventType = 'tool_result'
                } else if (role == 'user') {
                    eventType = containsContextTag(contentItem) ? 'context' : 'query'
                } else if (role == 'assistant') {
                    eventType = isSummaryContent(contentItem) ? 'summary' : 'response'
                } else {
                    eventType = 'context'
                }
                Map eventCore = [
                    sessionId: sessionId,
                    sequence: timeline.size() + 1,
                    sourceLine: record.lineNumber,
                    contentIndex: contentIndex,
                    role: role,
                    type: eventType,
                    sourceType: sourceType,
                    content: contentItem,
                    provenance: [sourcePath: record.sourcePath, sourceLine: record.lineNumber, contentIndex: contentIndex]
                ]
                String eventId = stableId(eventCore)
                Map event = new LinkedHashMap(eventCore)
                event.eventId = eventId
                event.causedBy = previousEventId
                previousEventId = eventId
                timeline << event
                if (eventType == 'query') queries << event
                if (eventType == 'response') responses << event
                if (eventType == 'context') context << event
                if (eventType == 'tool_call') toolCalls << event
                if (eventType == 'tool_result') toolResults << event
                if (eventType == 'summary') summaries << event
            }
        }
        [timeline: timeline, queries: queries, responses: responses, context: context, toolCalls: toolCalls, toolResults: toolResults, summaries: summaries]
    }

    boolean containsContextTag(Map contentItem) {
        String text = contentItem.text?.toString() ?: canonicalJson(contentItem)
        ['<user_info>', '<open_and_recently_viewed_files>', '<system_reminder>', '<rules>', '<attached_files>'].any { text.contains(it) }
    }

    boolean isSummaryContent(Map contentItem) {
        String text = contentItem.text?.toString()?.toLowerCase() ?: ''
        text.contains('<summary_content>') || text.contains('conversation was summarized') || text.startsWith('summary:')
    }

    Map exportScripts(String sessionId, List<Map> toolCalls, Path destination, Path existingDestination) {
        String prefix = sessionId.take(8)
        List<Map> operations = []
        List<Map> revisions = []
        Map<String, String> latestContent = [:]
        Set<String> paths = new LinkedHashSet<>()
        toolCalls.each { Map event ->
            Map content = event.content as Map
            String toolName = normalizeToolName(content.name?.toString())
            Map input = content.input instanceof Map ? content.input as Map : [:]
            if (FILE_TOOLS.contains(toolName)) {
                List<String> candidatePaths = extractMutationPaths(input)
                if (candidatePaths.isEmpty()) {
                    candidatePaths = extractPaths(canonicalJson(input)).toList()
                }
                Map operation = [
                    eventId: event.eventId,
                    sessionId: sessionId,
                    sequence: event.sequence,
                    tool: content.name,
                    operation: toolName,
                    paths: candidatePaths,
                    input: input,
                    provenance: event.provenance
                ]
                operations << operation
                candidatePaths.each { paths << it }
                reconstructRevision(operation, latestContent, revisions)
            }
            if (toolName == 'shell') {
                String command = input.command?.toString()
                if (command) {
                    List<String> candidates = extractShellFileCandidates(command)
                    if (!candidates.isEmpty()) {
                        operations << [
                            eventId: event.eventId,
                            sessionId: sessionId,
                            sequence: event.sequence,
                            tool: content.name,
                            operation: 'shell_file_candidate',
                            paths: candidates,
                            input: [command: command],
                            provenance: event.provenance
                        ]
                        candidates.each { paths << it }
                    }
                }
            }
        }
        if (operations.isEmpty()) {
            return [operationCount: 0, revisionCount: 0, snapshotCount: 0]
        }
        Path scriptsDir = destination.resolve('scripts')
        Files.createDirectories(scriptsDir)
        writeJsonLines(scriptsDir.resolve("${prefix}-file-operations.jsonl"), operations)
        writeConditionalJsonLines(scriptsDir, "${prefix}-revisions.jsonl", revisions)
        Path patchesDir = scriptsDir.resolve('patches')
        operations.findAll { it.operation == 'applypatch' }.each { Map operation ->
            Files.createDirectories(patchesDir)
            String payload = canonicalJson(operation.input)
            Files.writeString(patchesDir.resolve("${operation.eventId}.patch"), payload, StandardCharsets.UTF_8)
        }
        List<Map> snapshots = []
        paths.each { String rawPath ->
            Path path = resolveWorkspacePath(rawPath)
            if (path != null && Files.isRegularFile(path)) {
                long size = Files.size(path)
                if (size <= MAX_SNAPSHOT_BYTES) {
                    String safeName = safeFileName(path)
                    Path relative = Paths.get('scripts', 'snapshots', 'final', safeName)
                    Path target = destination.resolve(relative)
                    Files.createDirectories(target.parent)
                    copyWithReuse(path, target, existingDestination?.resolve(relative))
                    snapshots << [sourcePath: path.toString(), exportPath: relative.toString(), size: size, sha256: sha256(path)]
                } else {
                    completeness << issue(sessionId, 'snapshot_too_large', path.toString(), null, size.toString())
                }
            } else if (rawPath) {
                completeness << issue(sessionId, 'snapshot_unavailable', rawPath)
            }
        }
        writeJson(scriptsDir.resolve("${prefix}-script-index.json"), [operations: operations.size(), revisions: revisions.size(), snapshots: snapshots])
        [operationCount: operations.size(), revisionCount: revisions.size(), snapshotCount: snapshots.size(), snapshots: snapshots]
    }

    void reconstructRevision(Map operation, Map<String, String> latestContent, List<Map> revisions) {
        Map input = operation.input as Map
        String path = (operation.paths as List<String>).find()
        if (!path) {
            return
        }
        String before = latestContent[path]
        String after = null
        String operationName = operation.operation
        if (operationName == 'write') {
            after = input.contents?.toString() ?: input.content?.toString()
        } else if (operationName == 'edit' && before != null) {
            String oldString = input.old_string?.toString()
            String newString = input.new_string?.toString()
            if (oldString != null && newString != null && before.contains(oldString)) {
                after = before.replaceFirst(Pattern.quote(oldString), java.util.regex.Matcher.quoteReplacement(newString))
            }
        } else if (operationName == 'delete') {
            after = null
        }
        if (after != null || operationName == 'delete') {
            latestContent[path] = after
            revisions << [
                eventId: operation.eventId,
                path: path,
                operation: operationName,
                beforeSha256: before == null ? null : sha256(before),
                afterSha256: after == null ? null : sha256(after),
                content: after
            ]
        }
    }

    List<String> extractMutationPaths(Map input) {
        ['path', 'file_path', 'target_file', 'target_notebook'].collect { input[it]?.toString() }.findAll { it }.unique()
    }

    List<String> extractShellFileCandidates(String command) {
        Set<String> paths = new LinkedHashSet<>()
        def matcher = (command =~ /(?m)(?:>>?|tee(?:\s+-a)?|cp|mv)\s+["']?([\/~.A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+|\/[A-Za-z0-9_.-]+))["']?/)
        while (matcher.find()) {
            String candidate = matcher.group(1)
            if (candidate.contains('/') || candidate ==~ /.*\.(?:sh|groovy|py|js|ts|yaml|yml|json|jsonl|log|txt)$/) {
                paths << candidate
            }
        }
        paths.toList()
    }

    Map exportCommands(String sessionId, List<Map> toolCalls, Path destination, Path existingDestination) {
        String prefix = sessionId.take(8)
        List<Map> commands = []
        List<Map> results = []
        toolCalls.each { Map event ->
            Map content = event.content as Map
            String toolName = normalizeToolName(content.name?.toString())
            if (toolName != 'shell') {
                return
            }
            Map input = content.input instanceof Map ? content.input as Map : [:]
            String command = input.command?.toString()
            if (!command) {
                return
            }
            Map match = correlateTerminal(command, input.description?.toString())
            Map commandRecord = [
                eventId: event.eventId,
                sessionId: sessionId,
                sequence: event.sequence,
                command: command,
                description: input.description,
                workingDirectory: input.working_directory,
                timeoutMs: input.block_until_ms,
                notification: input.notify_on_output,
                correlation: match?.confidence ?: 'unmatched',
                terminalPath: match?.path,
                status: match?.status ?: 'unknown',
                exitCode: match?.exitCode,
                durationMs: match?.durationMs,
                startedAt: match?.startedAt,
                endedAt: match?.endedAt,
                provenance: event.provenance
            ]
            commands << commandRecord
            if (match) {
                results << [
                    eventId: stableId([commandEventId: event.eventId, terminalPath: match.path]),
                    commandEventId: event.eventId,
                    confidence: match.confidence,
                    status: match.status,
                    exitCode: match.exitCode,
                    durationMs: match.durationMs,
                    output: match.output,
                    sourcePath: match.path
                ]
            } else {
                completeness << issue(sessionId, 'command_result_unmatched', event.eventId?.toString(), event.sequence as Integer)
            }
        }
        if (commands.isEmpty()) {
            return [commandCount: 0, matchedCount: 0]
        }
        Path commandDir = destination.resolve('commands')
        Files.createDirectories(commandDir)
        writeJsonLines(commandDir.resolve("${prefix}-commands.jsonl"), commands)
        writeConditionalJsonLines(commandDir, "${prefix}-command-results.jsonl", results)
        List<Map> copiedLogs = []
        commands.findAll { it.terminalPath }.each { Map command ->
            Path source = Paths.get(command.terminalPath.toString())
            if (Files.isRegularFile(source)) {
                Path relative = Paths.get('commands', 'terminal-logs', source.fileName.toString())
                Path target = destination.resolve(relative)
                Files.createDirectories(target.parent)
                copyWithReuse(source, target, existingDestination?.resolve(relative))
                copiedLogs << [sourcePath: source.toString(), exportPath: relative.toString(), sha256: sha256(source)]
            }
        }
        writeJson(commandDir.resolve("${prefix}-command-index.json"), [commands: commands.size(), matched: results.size(), terminalLogs: copiedLogs.unique { it.sourcePath }])
        [commandCount: commands.size(), matchedCount: results.size()]
    }

    List<Map> loadTerminalRecords(Path terminalRoot) {
        if (terminalRoot == null || !Files.isDirectory(terminalRoot)) {
            return []
        }
        List<Map> records = []
        Files.list(terminalRoot).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.txt') }.sorted().forEach { Path path ->
                String text = readText(path)
                Map parsed = parseTerminalLog(path, text)
                if (parsed) {
                    records << parsed
                }
            }
        }
        records
    }

    Map parseTerminalLog(Path path, String text) {
        String command = extractTerminalQuotedField(text, 'command')
        if (!command) {
            def lastCommand = (text =~ /(?m)^last_command:\s*(.+)$/)
            if (lastCommand.find()) {
                command = lastCommand.group(1).trim()
            }
        }
        if (!command) {
            return null
        }
        String title = extractSimpleField(text, 'title')
        String status = extractSimpleField(text, 'status') ?: (extractSimpleField(text, 'exit_code') != null ? 'completed' : 'unknown')
        String exitCodeText = lastSimpleField(text, 'exit_code')
        String durationText = lastSimpleField(text, 'elapsed_ms') ?: extractSimpleField(text, 'running_for_ms')
        String startedAt = extractSimpleField(text, 'started_at')
        String endedAt = lastSimpleField(text, 'ended_at')
        String output = extractTerminalBody(text)
        [
            path: path.toAbsolutePath().normalize().toString(),
            command: command,
            normalizedCommand: normalizeCommand(command),
            title: title,
            status: status,
            exitCode: exitCodeText?.isInteger() ? exitCodeText.toInteger() : null,
            durationMs: durationText?.isLong() ? durationText.toLong() : null,
            startedAt: startedAt,
            endedAt: endedAt,
            output: output
        ]
    }

    String extractTerminalQuotedField(String text, String field) {
        Pattern pattern = Pattern.compile('(?s)^' + Pattern.quote(field) + ':\\s*"(.*?)"\\s*$', Pattern.MULTILINE)
        def matcher = pattern.matcher(text)
        if (!matcher.find()) {
            return null
        }
        try {
            return new JsonSlurper().parseText("\"${matcher.group(1)}\"").toString()
        } catch (Throwable ignored) {
            return matcher.group(1)
        }
    }

    String extractSimpleField(String text, String field) {
        def matcher = Pattern.compile("(?m)^${Pattern.quote(field)}:\\s*\"?([^\"\\n]+)\"?\\s*\$").matcher(text)
        matcher.find() ? matcher.group(1).trim() : null
    }

    String lastSimpleField(String text, String field) {
        def matcher = Pattern.compile("(?m)^${Pattern.quote(field)}:\\s*\"?([^\"\\n]+)\"?\\s*\$").matcher(text)
        String result = null
        while (matcher.find()) {
            result = matcher.group(1).trim()
        }
        result
    }

    String extractTerminalBody(String text) {
        List<String> chunks = text.split('(?m)^---\\s*$') as List<String>
        chunks.size() >= 3 ? chunks[1].replaceFirst('^\\s+', '').replaceFirst('\\s+$', '') : ''
    }

    Map correlateTerminal(String command, String description) {
        List<Map> available = terminalRecords.findAll { !usedTerminalPaths.contains(it.path) }
        Map match = available.find { it.command == command }
        String confidence = 'exact'
        if (!match) {
            String normalized = normalizeCommand(command)
            match = available.find { it.normalizedCommand == normalized }
            confidence = 'exact'
        }
        if (!match && description) {
            match = available.find { it.title?.equalsIgnoreCase(description) }
            confidence = 'inferred'
        }
        if (!match) {
            return null
        }
        usedTerminalPaths << match.path
        new LinkedHashMap(match) + [confidence: confidence]
    }

    Map exportReferencedArtifacts(String sessionId, List<Map> records, Path destination, Path existingDestination) {
        String prefix = sessionId.take(8)
        Set<String> referencedPaths = new LinkedHashSet<>()
        records.each { Map record ->
            extractPaths(canonicalJson(record.raw)).each { String path ->
                if (path.contains('/agent-tools/') || path.contains('/terminals/')) {
                    referencedPaths << path
                }
            }
        }
        List<Map> artifacts = []
        referencedPaths.each { String rawPath ->
            Path source
            try {
                source = Paths.get(rawPath).toAbsolutePath().normalize()
            } catch (Throwable ignored) {
                source = null
            }
            if (source != null && Files.isRegularFile(source) && Files.size(source) <= MAX_SNAPSHOT_BYTES) {
                Path relative = Paths.get('artifacts', 'files', safeFileName(source))
                Path target = destination.resolve(relative)
                Files.createDirectories(target.parent)
                copyWithReuse(source, target, existingDestination?.resolve(relative))
                artifacts << [sourcePath: source.toString(), exportPath: relative.toString(), size: Files.size(source), sha256: sha256(source)]
            } else {
                completeness << issue(sessionId, 'artifact_unavailable', rawPath)
            }
        }
        if (!artifacts.isEmpty()) {
            Path artifactsDir = destination.resolve('artifacts')
            Files.createDirectories(artifactsDir)
            writeJson(artifactsDir.resolve("${prefix}-artifacts.json"), [artifacts: artifacts])
        }
        [artifactCount: artifacts.size(), artifacts: artifacts]
    }

    Map exportWorkspace(String sessionId, Map normalized, Path destination) {
        String prefix = sessionId.take(8)
        Path workspace = options.workspace as Path
        Map runtime = [
            collectedAt: Instant.now().toString(),
            osName: System.getProperty('os.name'),
            osVersion: System.getProperty('os.version'),
            osArch: System.getProperty('os.arch'),
            javaVersion: System.getProperty('java.version'),
            groovyVersion: GroovySystem.version,
            userDirectory: System.getProperty('user.dir'),
            workspace: workspace?.toString()
        ]
        Map git = collectGitState(workspace)
        Set<String> openFiles = new TreeSet<>()
        (normalized.context as List<Map>).each { Map event ->
            extractPaths(canonicalJson(event.content)).each { openFiles << it }
        }
        List<Map> availableRules = (normalized.context as List<Map>).findAll {
            canonicalJson(it.content).contains('<rules>') || canonicalJson(it.content).contains('<user_rule>')
        }
        Path workspaceDir = destination.resolve('workspace')
        Files.createDirectories(workspaceDir)
        writeJson(workspaceDir.resolve("${prefix}-environment.json"), runtime)
        writeJson(workspaceDir.resolve("${prefix}-git-state.json"), git)
        writeJson(workspaceDir.resolve("${prefix}-open-files.json"), [paths: openFiles.toList()])
        writeJson(workspaceDir.resolve("${prefix}-user-rules.json"), [available: !availableRules.isEmpty(), events: availableRules])
        writeJson(workspaceDir.resolve("${prefix}-runtime-versions.json"), [java: runtime.javaVersion, groovy: runtime.groovyVersion, os: runtime.osName])
        [runtime: runtime, git: git, openFiles: openFiles.toList(), userRulesAvailable: !availableRules.isEmpty()]
    }

    Map collectGitState(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace.resolve('.git'))) {
            return [available: false, collectedAt: Instant.now().toString()]
        }
        [
            available: true,
            collectedAt: Instant.now().toString(),
            branch: runProcess(['git', 'branch', '--show-current'], workspace),
            status: runProcess(['git', 'status', '--short'], workspace),
            diffStat: runProcess(['git', 'diff', '--stat'], workspace),
            head: runProcess(['git', 'rev-parse', 'HEAD'], workspace)
        ]
    }

    Map runProcess(List<String> command, Path directory) {
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(false).start()
            boolean completed = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return [exitCode: null, stdout: '', stderr: 'timeout']
            }
            [exitCode: process.exitValue(), stdout: process.inputStream.getText(StandardCharsets.UTF_8.name()).trim(), stderr: process.errorStream.getText(StandardCharsets.UTF_8.name()).trim()]
        } catch (Throwable failure) {
            [exitCode: null, stdout: '', stderr: failure.message]
        }
    }

    Map buildSearchIndex(List<Map> timeline) {
        Map<String, Set<String>> index = [
            paths: new TreeSet<>(),
            hosts: new TreeSet<>(),
            urls: new TreeSet<>(),
            identifiers: new TreeSet<>(),
            tools: new TreeSet<>()
        ]
        timeline.each { Map event ->
            String text = canonicalJson(event.content)
            extractPaths(text).each { index.paths << it }
            collectMatches(HOST_PATTERN, text).each { index.hosts << it }
            collectMatches(URL_PATTERN, text).each { index.urls << it }
            collectMatches(ID_PATTERN, text).each { index.identifiers << it }
            if (event.type == 'tool_call' && event.content.name) {
                index.tools << event.content.name.toString()
            }
        }
        index.collectEntries { key, value -> [key, (value as Set).toList()] }
    }

    Map buildCheckpoint(String sessionId, List<Map> timeline, List<Map> issues) {
        List<Map> recent = timeline.findAll { it.type in ['query', 'response', 'summary'] }.takeRight(20).collect {
            [eventId: it.eventId, sequence: it.sequence, role: it.role, type: it.type, text: it.content.text]
        }
        [sessionId: sessionId, lastSequence: timeline ? timeline.last().sequence : 0, recentConversation: recent, completenessIssues: issues]
    }

    Map buildRestoreContext(String sessionId, List<Map> timeline, Map checkpoint, Map workspace, Map scripts) {
        List<Map> recent = checkpoint.recentConversation as List<Map>
        String conversation = recent.collect { "${it.role}: ${it.text ?: ''}" }.join('\n\n')
        String bootstrap = "Restore Cursor session ${sessionId}. Use the supplied manifest, timeline, scripts, commands, artifacts, workspace metadata, and referenced sessions as authoritative evidence. Do not claim unavailable command results. Continue from this checkpoint:\n\n${conversation}"
        [
            schemaVersion: 1,
            sessionId: sessionId,
            sourceTranscript: transcriptIndex[sessionId].toString(),
            workspace: workspace,
            requiredFiles: scripts.snapshots ?: [],
            checkpoint: checkpoint,
            bootstrapPrompt: bootstrap
        ]
    }

    void writeRootRelationshipFiles(Path staging, String rootSession, Map graph, Map rootResult) {
        String prefix = rootSession.take(8)
        writeJson(staging.resolve("${prefix}-reference-graph.json"), graph)
        Path integrityDir = staging.resolve('integrity')
        Files.createDirectories(integrityDir)
        List<Map> issues = (completeness + (graph.unresolved as List)).sort { a, b -> canonicalJson(a) <=> canonicalJson(b) }
        writeJson(integrityDir.resolve("${prefix}-missing-items.json"), [items: issues])
        writeJson(integrityDir.resolve("${prefix}-unmatched-results.json"), [items: issues.findAll { it.type == 'command_result_unmatched' }])
        writeJson(integrityDir.resolve("${prefix}-export-report.json"), [
            rootSession: rootSession,
            sessionCount: (graph.sessions as List).size(),
            referenceCount: (graph.sessions as List).size() - 1,
            completenessIssues: issues.size(),
            reusedArtifacts: reusedArtifacts,
            generatedAt: Instant.now().toString()
        ])
    }

    void writeIntegrity(Path staging) {
        String rootSession = staging.fileName.toString().replaceFirst(/^\\./, '').split(/\\.staging-/)[0]
        if (!(rootSession ==~ /[0-9a-f-]{36}/)) {
            Path manifest = Files.list(staging).withCloseable { stream -> stream.filter { it.fileName.toString().endsWith('-manifest.json') }.findFirst().orElse(null) }
            if (manifest != null) {
                rootSession = new JsonSlurper().parse(manifest.toFile()).sessionId
            }
        }
        String prefix = rootSession.take(8)
        List<Map> checksums = []
        Files.walk(staging).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { Path path ->
                Path relative = staging.relativize(path)
                if (!relative.toString().startsWith('integrity/')) {
                    checksums << [path: relative.toString(), size: Files.size(path), sha256: sha256(path)]
                }
            }
        }
        Path integrityDir = staging.resolve('integrity')
        Files.createDirectories(integrityDir)
        writeJson(integrityDir.resolve("${prefix}-checksums.json"), [algorithm: 'SHA-256', files: checksums])
    }

    Map validateBundle(Path root) {
        List<String> errors = []
        if (!Files.isDirectory(root)) {
            return [valid: false, errors: ["bundle not found: ${root}"], checkedFiles: 0]
        }
        int checked = 0
        Map<Path, Integer> timelineCounts = [:]
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { Path path ->
                checked++
                try {
                    if (path.fileName.toString().endsWith('.json')) {
                        new JsonSlurper().parse(path.toFile())
                    } else if (path.fileName.toString().endsWith('.jsonl') && !path.fileName.toString().endsWith('-raw-transcript.jsonl')) {
                        int line = 0
                        Set<String> eventIds = new HashSet<>()
                        boolean canonicalTimeline = path.fileName.toString().contains('-session.jsonl')
                        int expectedSequence = 1
                        path.toFile().eachLine(StandardCharsets.UTF_8.name()) { String text ->
                            line++
                            if (text.trim()) {
                                Object parsed = new JsonSlurper().parseText(text)
                                if (canonicalTimeline) {
                                    if (!(parsed instanceof Map)) {
                                        errors << "non-object timeline event in ${path}:${line}"
                                    } else {
                                        Map event = parsed as Map
                                        ['eventId', 'sessionId', 'sequence', 'type', 'provenance'].each { String field ->
                                            if (!event.containsKey(field) || event[field] == null) {
                                                errors << "missing ${field} in ${path}:${line}"
                                            }
                                        }
                                        if (event.eventId && !eventIds.add(event.eventId.toString())) {
                                            errors << "duplicate eventId ${event.eventId} in ${path}"
                                        }
                                        if (event.sequence != expectedSequence) {
                                            errors << "unexpected sequence ${event.sequence} in ${path}:${line}; expected ${expectedSequence}"
                                        }
                                        expectedSequence++
                                    }
                                }
                            }
                        }
                        if (canonicalTimeline) {
                            timelineCounts[path.parent.parent] = line
                        }
                    }
                } catch (Throwable failure) {
                    errors << "invalid ${path}: ${failure.message}"
                }
            }
        }
        List<Path> manifestFiles = []
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('-manifest.json') }.forEach { manifestFiles << it }
        }
        manifestFiles.each { Path manifestPath ->
            try {
                Map manifest = new JsonSlurper().parse(manifestPath.toFile()) as Map
                ['schemaVersion', 'sessionId', 'prefix', 'counts'].each { String field ->
                    if (!manifest.containsKey(field) || manifest[field] == null) {
                        errors << "manifest missing ${field}: ${manifestPath}"
                    }
                }
                Path sessionRoot = manifestPath.parent
                Integer actualEvents = timelineCounts[sessionRoot]
                Object declaredEvents = manifest.counts instanceof Map ? manifest.counts.events : null
                if (actualEvents != null && declaredEvents != actualEvents) {
                    errors << "manifest event count mismatch in ${manifestPath}: ${declaredEvents} != ${actualEvents}"
                }
                (manifest.paths instanceof List ? manifest.paths : []).each { Object rawPath ->
                    Path listed = sessionRoot.resolve(rawPath.toString()).normalize()
                    if (!listed.startsWith(sessionRoot.normalize())) {
                        errors << "manifest path escapes session: ${rawPath}"
                    } else if (!Files.exists(listed)) {
                        errors << "manifest path missing: ${rawPath}"
                    }
                }
            } catch (Throwable failure) {
                errors << "invalid manifest ${manifestPath}: ${failure.message}"
            }
        }
        List<Path> graphFiles = []
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('-reference-graph.json') }.forEach { graphFiles << it }
        }
        graphFiles.each { Path graphPath ->
            Map graph = new JsonSlurper().parse(graphPath.toFile()) as Map
            String graphRoot = graph.root?.toString()
            (graph.sessions instanceof List ? graph.sessions : []).each { Object rawSession ->
                String sessionId = rawSession.toString()
                Path expected = sessionId == graphRoot ? root : root.resolve('references').resolve(sessionId)
                if (!Files.isDirectory(expected)) {
                    errors << "reference target missing: ${sessionId}"
                }
            }
        }
        List<Path> checksumFiles = []
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('-checksums.json') }.forEach { checksumFiles << it }
        }
        checksumFiles.each { Path checksumPath ->
            Map checksumData = new JsonSlurper().parse(checksumPath.toFile()) as Map
            (checksumData.files as List<Map>).each { Map item ->
                Path target = root.resolve(item.path.toString()).normalize()
                if (!target.startsWith(root.normalize())) {
                    errors << "checksum path escapes bundle: ${item.path}"
                } else if (!Files.isRegularFile(target)) {
                    errors << "checksum target missing: ${item.path}"
                } else if (sha256(target) != item.sha256) {
                    errors << "checksum mismatch: ${item.path}"
                }
            }
        }
        [valid: errors.isEmpty(), errors: errors, checkedFiles: checked, checkedAt: Instant.now().toString()]
    }

    void copyWithReuse(Path source, Path target, Path existing) {
        if (existing != null && Files.isRegularFile(existing) && sha256(existing) == sha256(source)) {
            Files.copy(existing, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            reusedArtifacts++
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    Path resolveWorkspacePath(String rawPath) {
        if (!rawPath) {
            return null
        }
        String expanded = rawPath.startsWith('~/') ? System.getProperty('user.home') + rawPath.substring(1) : rawPath
        try {
            Path path = Paths.get(expanded)
            if (!path.isAbsolute()) {
                path = (options.workspace as Path).resolve(path)
            }
            path.toAbsolutePath().normalize()
        } catch (Throwable ignored) {
            null
        }
    }

    Set<String> extractPaths(String text) {
        Set<String> paths = new LinkedHashSet<>()
        def matcher = ABSOLUTE_PATH_PATTERN.matcher(text)
        while (matcher.find()) {
            paths << matcher.group(1).replaceAll('\\\\[nrt]', '')
        }
        paths
    }

    List<String> collectMatches(Pattern pattern, String text) {
        List<String> values = []
        def matcher = pattern.matcher(text)
        while (matcher.find()) {
            values << matcher.group()
        }
        values.unique().sort()
    }

    String normalizeToolName(String name) {
        if (!name) {
            return ''
        }
        name.tokenize('.').last().replaceAll('[^A-Za-z]', '').toLowerCase()
    }

    String normalizeCommand(String command) {
        command?.replaceAll('\\s+', ' ')?.trim()
    }

    Map issue(String sessionId, String type, String source, Integer line = null, String detail = null) {
        [sessionId: sessionId, type: type, source: source, line: line, detail: detail].findAll { it.value != null }
    }

    void writeConditionalJsonLines(Path directory, String fileName, List<Map> items) {
        if (!items.isEmpty()) {
            writeJsonLines(directory.resolve(fileName), items)
        }
    }

    void writeJsonLines(Path path, List items) {
        Files.createDirectories(path.parent)
        path.toFile().withWriter(StandardCharsets.UTF_8.name()) { writer ->
            items.each { item ->
                writer.write(canonicalJson(item))
                writer.write('\n')
            }
        }
    }

    void writeJson(Path path, Object value) {
        Files.createDirectories(path.parent)
        String compact = canonicalJson(value)
        Files.writeString(path, JsonOutput.prettyPrint(compact) + '\n', StandardCharsets.UTF_8)
    }

    static String canonicalJson(Object value) {
        JsonOutput.toJson(canonicalize(value))
    }

    static Object canonicalize(Object value) {
        if (value instanceof Map) {
            Map sorted = new TreeMap<>()
            value.each { key, item -> sorted[key.toString()] = canonicalize(item) }
            return sorted
        }
        if (value instanceof Collection) {
            return value.collect { canonicalize(it) }
        }
        if (value != null && value.class.isArray()) {
            return (value as Object[]).collect { canonicalize(it) }
        }
        value
    }

    static String stableId(Object value) {
        sha256(canonicalJson(value)).take(24)
    }

    static String sha256(String value) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        digest.digest(value.getBytes(StandardCharsets.UTF_8)).collect { String.format('%02x', it) }.join()
    }

    static String sha256(Path path) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        path.toFile().withInputStream { input ->
            byte[] buffer = new byte[8192]
            int count
            while ((count = input.read(buffer)) > 0) {
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().collect { String.format('%02x', it) }.join()
    }

    static String readText(Path path) {
        Files.readString(path, StandardCharsets.UTF_8)
    }

    static String safeFileName(Path path) {
        String flattened = path.toString().replaceAll('^[\\\\/]+', '').replaceAll('[^A-Za-z0-9._-]+', '__')
        "${sha256(path.toString()).take(12)}-${flattened.takeRight(180)}"
    }

    static List<String> listRelativeFiles(Path root) {
        List<String> files = []
        Files.walk(root).withCloseable { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { files << root.relativize(it).toString() }
        }
        files.sort()
    }

    static boolean isDirectoryEmpty(Path path) {
        Files.list(path).withCloseable { stream -> !stream.findAny().isPresent() }
    }

    static void movePath(Path source, Path destination) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination)
        }
    }

    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Files.deleteIfExists(file)
                FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult postVisitDirectory(Path directory, IOException error) {
                Files.deleteIfExists(directory)
                FileVisitResult.CONTINUE
            }
        })
    }
}

Path sourceLocation
try {
    sourceLocation = Paths.get(getClass().protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
} catch (Throwable ignored) {
    sourceLocation = Paths.get(System.getProperty('user.dir')).toAbsolutePath().normalize()
}
Path exporterDirectory = Files.isDirectory(sourceLocation) ? sourceLocation : sourceLocation.parent
if (exporterDirectory == null || !Files.exists(exporterDirectory.resolve('session-exporter.groovy'))) {
    exporterDirectory = Paths.get(System.getProperty('user.dir')).toAbsolutePath().normalize()
}
System.exit(SessionExporter.run(args, exporterDirectory))
