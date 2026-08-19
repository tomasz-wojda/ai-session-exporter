#!/usr/bin/env groovy

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
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
    static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString('rwx------')
    static final Set<PosixFilePermission> FILE_PERMISSIONS = PosixFilePermissions.fromString('rw-------')
    static final Map<String, Integer> CONFIDENCE_RANK = [low: 1, medium: 2, high: 3]
    static final Map<String, Integer> EVIDENCE_RANK = [tool_input: 1, shell_command: 2, file_content: 3, transcript_path: 4, summary: 5, message: 6, explicit_session_link: 7]
    static final Set<String> REFERENCE_SCOPES = ['recursive', 'direct', 'relevant', 'none'] as Set
    static final int CONFIG_VERSION = 1
    static final Map<String, String> PATH_OPTIONS = [
        '--output-dir': 'outputDir',
        '--transcript-root': 'transcriptRoot',
        '--terminal-root': 'terminalRoot',
        '--agent-tool-root': 'agentToolRoot',
        '--workspace': 'workspace'
    ]
    static final Set<String> CONFIG_KEYS = (PATH_OPTIONS.values() + ['referenceScope']) as Set
    static final Set<String> PROJECT_CONFIG_KEYS = ['transcriptRoot', 'terminalRoot', 'agentToolRoot', 'workspace'] as Set

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
    Map activeGraph = [:]

    SessionExporter(Map options, Path scriptDir) {
        this.options = options
        this.scriptDir = scriptDir
        this.outputRoot = options.containsKey('outputDir') ? options.get('outputDir') as Path : scriptDir.resolve('sessions-export')
    }

    static int run(String[] args, Path scriptDir) {
        try {
            SessionExporter configSupport = new SessionExporter([:], scriptDir)
            if (args && args[0] == 'config') {
                configSupport.configure(args.drop(1) as String[])
                return 0
            }
            Map options = parseArguments(args, configSupport.loadConfig())
            SessionExporter exporter = new SessionExporter(options, scriptDir)
            exporter.execute()
            return 0
        } catch (ExportFailure failure) {
            System.err.println("cursor-session-exporter: ${failure.message}")
            return failure.exitCode
        } catch (Throwable failure) {
            System.err.println("cursor-session-exporter: ${failure.class.simpleName}: ${failure.message}")
            return 9
        }
    }

    static Map parseArguments(String[] args, Map savedConfig = [version: CONFIG_VERSION]) {
        if (!args || args[0] in ['--help', '-h']) {
            throw new ExportFailure(2, 'usage: cursor-session-exporter <session_id> [--reference-scope recursive|direct|relevant|none] [--output-dir PATH] [--transcript-root PATH] [--terminal-root PATH] [--agent-tool-root PATH] [--workspace PATH] [--validate-only] | cursor-session-exporter config [persistent options] [--unset KEY]')
        }
        Map options = [sessionId: args[0], validateOnly: false, referenceScope: 'relevant']
        savedConfig.each { String key, Object value ->
            if (PATH_OPTIONS.containsValue(key)) {
                options[key] = Paths.get(value.toString()).toAbsolutePath().normalize()
            } else if (key == 'referenceScope') {
                options.referenceScope = value.toString()
            }
        }
        int index = 1
        while (index < args.length) {
            String argument = args[index]
            if (argument == '--validate-only') {
                options.validateOnly = true
                index++
                continue
            }
            if (argument == '--reference-scope') {
                if (index + 1 >= args.length || !REFERENCE_SCOPES.contains(args[index + 1])) {
                    throw new ExportFailure(2, 'reference scope must be recursive, direct, relevant, or none')
                }
                options.referenceScope = args[index + 1]
                index += 2
                continue
            }
            if (!PATH_OPTIONS.containsKey(argument) || index + 1 >= args.length) {
                throw new ExportFailure(2, "invalid argument: ${argument}")
            }
            String key = PATH_OPTIONS[argument]
            options[key] = Paths.get(args[index + 1]).toAbsolutePath().normalize()
            index += 2
        }
        String sessionId = options.sessionId as String
        if (!(sessionId ==~ /(?i)([0-9a-f]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/)) {
            throw new ExportFailure(3, "malformed session identifier: ${sessionId}")
        }
        options
    }

    void configure(String[] args) {
        if (args && args[0] in ['--help', '-h']) {
            throw new ExportFailure(2, 'usage: cursor-session-exporter config [--output-dir PATH] [--transcript-root PATH] [--terminal-root PATH] [--agent-tool-root PATH] [--workspace PATH] [--reference-scope recursive|direct|relevant|none] [--unset KEY]')
        }
        Map stored = loadConfig()
        if (!args) {
            println(JsonOutput.prettyPrint(canonicalJson(stored)))
            return
        }
        Map updates = [:]
        Set<String> removals = new LinkedHashSet<>()
        int index = 0
        while (index < args.length) {
            String argument = args[index]
            if (argument == '--unset') {
                if (index + 1 >= args.length || !CONFIG_KEYS.contains(args[index + 1])) {
                    throw new ExportFailure(2, '--unset requires one of: ' + CONFIG_KEYS.sort().join(', '))
                }
                removals << args[index + 1]
                index += 2
                continue
            }
            if (argument == '--reference-scope') {
                if (index + 1 >= args.length || !REFERENCE_SCOPES.contains(args[index + 1])) {
                    throw new ExportFailure(2, 'reference scope must be recursive, direct, relevant, or none')
                }
                updates.referenceScope = args[index + 1]
                index += 2
                continue
            }
            if (!PATH_OPTIONS.containsKey(argument) || index + 1 >= args.length) {
                throw new ExportFailure(2, "invalid config argument: ${argument}")
            }
            String rawPath = args[index + 1]
            if (!rawPath.trim()) {
                throw new ExportFailure(2, "${argument} requires a non-empty path")
            }
            updates[PATH_OPTIONS[argument]] = Paths.get(rawPath).toAbsolutePath().normalize().toString()
            index += 2
        }
        if (!updates.isEmpty() && !removals.isEmpty()) {
            throw new ExportFailure(2, 'config values and --unset cannot be combined')
        }
        Map result = new LinkedHashMap(stored)
        updates.each { key, value -> result[key] = value }
        removals.each { result.remove(it) }
        result.version = CONFIG_VERSION
        saveConfig(result)
        if (!updates.keySet().intersect(PROJECT_CONFIG_KEYS).isEmpty()) {
            System.err.println('cursor-session-exporter: warning: saved project-specific paths apply to every export unless overridden on the command line')
        }
        println(JsonOutput.prettyPrint(canonicalJson(result)))
    }

    Path configDirectory() {
        Paths.get(System.getProperty('user.home'), '.cursor-session-exporter').toAbsolutePath().normalize()
    }

    Path configPath() {
        configDirectory().resolve('config.json')
    }

    Map loadConfig() {
        Path path = configPath()
        if (!Files.exists(path)) {
            return [version: CONFIG_VERSION]
        }
        validateConfigPermissions(path)
        Object parsed
        try {
            parsed = new JsonSlurper().parse(path.toFile())
        } catch (Throwable failure) {
            throw new ExportFailure(2, "invalid config JSON at ${path}: ${failure.message}")
        }
        if (!(parsed instanceof Map)) {
            throw new ExportFailure(2, "config must be a JSON object: ${path}")
        }
        Map config = parsed as Map
        Set<String> allowed = (CONFIG_KEYS + ['version']) as Set
        Set<String> unknown = config.keySet().collect { it.toString() }.findAll { !allowed.contains(it) } as Set
        if (!unknown.isEmpty()) {
            throw new ExportFailure(2, "unknown config keys at ${path}: ${unknown.sort().join(', ')}")
        }
        if (config.version != CONFIG_VERSION) {
            throw new ExportFailure(2, "unsupported config version at ${path}: ${config.version}")
        }
        CONFIG_KEYS.each { String key ->
            if (!config.containsKey(key)) {
                return
            }
            Object value = config[key]
            if (!(value instanceof String) || !value.toString().trim()) {
                throw new ExportFailure(2, "config value must be a non-empty string: ${key}")
            }
            if (key == 'referenceScope') {
                if (!REFERENCE_SCOPES.contains(value.toString())) {
                    throw new ExportFailure(2, "invalid reference scope in config: ${value}")
                }
            } else if (!Paths.get(value.toString()).isAbsolute()) {
                throw new ExportFailure(2, "config path must be absolute: ${key}")
            }
        }
        config
    }

    void saveConfig(Map config) {
        Path directory = configDirectory()
        Path destination = configPath()
        Path temporary = directory.resolve(".config-${UUID.randomUUID()}.tmp")
        secureDirectories(directory)
        try {
            if (supportsPosix(directory)) {
                Files.createFile(temporary, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
            } else {
                Files.createFile(temporary)
                secureFile(temporary)
            }
            Files.writeString(temporary, JsonOutput.prettyPrint(canonicalJson(config)) + '\n', StandardCharsets.UTF_8)
            secureFile(temporary)
            new JsonSlurper().parse(temporary.toFile())
            replacePath(temporary, destination)
            secureFile(destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    void validateConfigPermissions(Path path) {
        if (!supportsPosix(path)) {
            return
        }
        if (Files.getPosixFilePermissions(path) != FILE_PERMISSIONS) {
            throw new ExportFailure(2, "config file permissions must be 0600: ${path}")
        }
        Path directory = path.parent
        if (directory != null && Files.getPosixFilePermissions(directory) != DIRECTORY_PERMISSIONS) {
            throw new ExportFailure(2, "config directory permissions must be 0700: ${directory}")
        }
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
        secureDirectories(outputRoot)
        Path mainTranscript = transcriptIndex[rootSession]
        configureEvidenceRoots(mainTranscript)
        terminalRecords = loadTerminalRecords(options.terminalRoot as Path)
        Map graph = discoverReferences(rootSession)
        applyReferenceScope(graph, options.referenceScope as String)
        activeGraph = graph
        Path staging = outputRoot.resolve(".${rootSession}.staging-${UUID.randomUUID()}")
        Path previous = outputRoot.resolve(".${rootSession}.previous-${UUID.randomUUID()}")
        deleteRecursively(staging)
        secureDirectories(staging)
        try {
            Map rootResult = exportOneSession(rootSession, staging, false, destination)
            Path referenceRoot = staging.resolve('references')
            ((graph.exportedSessions as List<String>).findAll { it != rootSession }).each { String referenceId ->
                exportOneSession(referenceId, referenceRoot.resolve(referenceId), true, destination.resolve('references').resolve(referenceId))
            }
            if (Files.exists(referenceRoot) && isDirectoryEmpty(referenceRoot)) {
                Files.delete(referenceRoot)
            }
            writeRootRelationshipFiles(staging, rootSession, graph, rootResult)
            writeIntegrity(staging)
            hardenTree(staging)
            Map validation = validateBundle(staging)
            writeJson(staging.resolve('integrity').resolve("${rootSession.take(8)}-validation.json"), validation)
            if (!validation.valid) {
                throw new ExportFailure(8, "validation failed: ${(validation.errors as List).join('; ')}")
            }
            hardenTree(staging)
            boolean hadDestination = Files.exists(destination)
            if (hadDestination) {
                movePath(destination, previous)
            }
            try {
                movePath(staging, destination)
                hardenTree(destination)
                deleteRecursively(previous)
            } catch (Throwable moveFailure) {
                if (hadDestination && Files.exists(previous) && !Files.exists(destination)) {
                    movePath(previous, destination)
                    hardenTree(destination)
                }
                throw moveFailure
            }
            println("Exported ${rootSession} to ${destination}")
            println("References: detected=${(graph.sessions as List).size() - 1}, direct=${graph.summary.direct}, indirect=${graph.summary.indirect}, exported=${(graph.exportedSessions as List).size() - 1}, omitted=${(graph.omittedSessions as List).size()}, cycles=${(graph.cycleGroups as List).size()}, reused artifacts: ${reusedArtifacts}")
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
        Map<String, List<Map>> evidenceByEdge = [:].withDefault { [] }
        while (!queue.isEmpty()) {
            String current = queue.remove(0)
            if (!visited.add(current)) {
                continue
            }
            Map<String, List<Map>> discovered = discoverDirectReferences(current, unresolved)
            discovered.each { String reference, List<Map> evidence ->
                evidenceByEdge["${current}|${reference}"].addAll(evidence)
                if (!visited.contains(reference)) {
                    queue << reference
                }
            }
        }
        List<Map> evidence = evidenceByEdge.values().flatten().sort { a, b -> "${a.from}:${a.to}:${a.sourceLine}:${a.contentIndex}:${a.evidenceId}" <=> "${b.from}:${b.to}:${b.sourceLine}:${b.contentIndex}:${b.evidenceId}" }
        List<Map> edges = evidenceByEdge.collect { String key, List<Map> occurrences ->
            List<String> parts = key.split('\\|', 2) as List<String>
            aggregateReferenceEdge(rootSession, parts[0], parts[1], occurrences)
        }.sort { a, b -> "${a.from}:${a.to}" <=> "${b.from}:${b.to}" }
        Map<String, Integer> depths = [(rootSession): 0]
        Map<String, String> parents = [:]
        List<String> depthQueue = [rootSession]
        while (!depthQueue.isEmpty()) {
            String source = depthQueue.remove(0)
            edges.findAll { it.from == source }.sort { it.to }.each { Map edge ->
                String target = edge.to
                if (!depths.containsKey(target)) {
                    depths[target] = depths[source] + 1
                    parents[target] = source
                    depthQueue << target
                }
            }
        }
        List<List<String>> cycleGroups = findCycleGroups(visited.toList(), edges)
        Map<String, Integer> cycleBySession = [:]
        cycleGroups.eachWithIndex { List<String> group, int index -> group.each { cycleBySession[it] = index + 1 } }
        edges.each { Map edge ->
            edge.cyclic = cycleBySession[edge.from] != null && cycleBySession[edge.from] == cycleBySession[edge.to]
        }
        List<Map> nodes = visited.collect { String sessionId ->
            List<String> vector = shortestVector(rootSession, sessionId, parents)
            List<Map> pathEdges = []
            for (int index = 0; index + 1 < vector.size(); index++) {
                Map edge = edges.find { it.from == vector[index] && it.to == vector[index + 1] }
                if (edge) pathEdges << edge
            }
            String baseRelationship = sessionId == rootSession ? 'root' : (depths[sessionId] == 1 ? 'direct' : 'indirect')
            String confidence = pathEdges.isEmpty() ? 'high' : weakestConfidence(pathEdges.collect { it.confidence as String })
            String relevance = classifyRelevance(baseRelationship, confidence, pathEdges)
            [
                sessionId: sessionId,
                prefix: sessionId.take(8),
                relationship: sessionId != rootSession && cycleBySession.containsKey(sessionId) ? 'cyclic' : baseRelationship,
                baseRelationship: baseRelationship,
                depth: depths[sessionId],
                shortestVector: vector,
                topic: topicForSession(sessionId),
                confidence: confidence,
                relevance: relevance,
                cycleGroup: cycleBySession[sessionId],
                exported: false
            ].findAll { it.value != null }
        }.sort { a, b ->
            int depthComparison = (a.depth as int) <=> (b.depth as int)
            depthComparison != 0 ? depthComparison : a.sessionId <=> b.sessionId
        }
        relationshipEdges = edges
        Map summary = buildReferenceSummary(rootSession, nodes, edges, cycleGroups)
        [
            schemaVersion: 2,
            referenceModelVersion: 1,
            root: rootSession,
            sessions: visited.toList(),
            nodes: nodes,
            edges: edges,
            evidence: evidence,
            cycleGroups: cycleGroups,
            summary: summary,
            unresolved: unresolved.sort { a, b -> canonicalJson(a) <=> canonicalJson(b) }
        ]
    }

    Map<String, List<Map>> discoverDirectReferences(String sessionId, List<Map> unresolved) {
        Map<String, List<Map>> references = [:].withDefault { [] }
        List<Map> records = parseTranscript(sessionId)
        Map<String, String> eventIds = normalizeEvents(sessionId, records).timeline.collectEntries { Map event ->
            ["${event.sourceLine}:${event.contentIndex}", event.eventId.toString()]
        }
        records.each { Map record ->
            Map raw = record.raw as Map
            String role = raw.role?.toString() ?: 'unknown'
            List content = raw.message instanceof Map && raw.message.content instanceof List ? raw.message.content as List : []
            content.eachWithIndex { Object item, int contentIndex ->
                Map contentItem = item instanceof Map ? item as Map : [type: 'value', value: item]
                String eventId = eventIds["${record.lineNumber}:${contentIndex}"]
                if (contentItem.type == 'text') {
                    String category = isSummaryContent(contentItem) ? 'summary' : 'message'
                    scanReferenceText(sessionId, contentItem.text?.toString() ?: '', category, role, null, 'text', record, contentIndex, eventId, references, unresolved)
                } else if (contentItem.type == 'tool_use') {
                    String tool = normalizeToolName(contentItem.name?.toString())
                    Object input = contentItem.input
                    collectStringLeaves(input, 'input').each { Map leaf ->
                        String category
                        if (leaf.value.contains('/agent-transcripts/') || leaf.value.contains('agent-transcripts/')) {
                            category = 'transcript_path'
                        } else if (tool == 'shell' && leaf.path.endsWith('.command')) {
                            category = 'shell_command'
                        } else if (FILE_TOOLS.contains(tool) && (tool == 'applypatch' || leaf.path ==~ /(?i).*(contents|content|old_string|new_string|patch).*/)) {
                            category = 'file_content'
                        } else {
                            category = 'tool_input'
                        }
                        scanReferenceText(sessionId, leaf.value, category, role, contentItem.name?.toString(), leaf.path, record, contentIndex, eventId, references, unresolved)
                    }
                }
            }
        }
        references
    }

    void scanReferenceText(String sessionId, String text, String baseCategory, String role, String tool, String fieldPath, Map record, int contentIndex, String eventId, Map<String, List<Map>> references, List<Map> unresolved) {
        def uuidMatcher = UUID_PATTERN.matcher(text)
        while (uuidMatcher.find()) {
            String identifier = uuidMatcher.group(1)
            String category = baseCategory == 'message' ? 'explicit_session_link' : baseCategory
            addReferenceOccurrence(sessionId, identifier, 'full_uuid', category, role, tool, fieldPath, text, record, contentIndex, eventId, references, unresolved)
        }
        def prefixMatcher = PREFIX_PATTERN.matcher(text)
        while (prefixMatcher.find()) {
            addReferenceOccurrence(sessionId, prefixMatcher.group(1), 'prefix', baseCategory, role, tool, fieldPath, text, record, contentIndex, eventId, references, unresolved)
        }
    }

    void addReferenceOccurrence(String sessionId, String identifier, String identifierForm, String category, String role, String tool, String fieldPath, String text, Map record, int contentIndex, String eventId, Map<String, List<Map>> references, List<Map> unresolved) {
        List<String> matches = identifierForm == 'full_uuid'
            ? (transcriptIndex.containsKey(identifier.toLowerCase()) ? [identifier.toLowerCase()] : [])
            : transcriptIndex.keySet().findAll { it.startsWith(identifier.toLowerCase()) }.toList()
        if (matches.size() > 1) {
            unresolved << [
                sessionId: sessionId,
                identifier: identifier,
                identifierForm: identifierForm,
                relationship: 'unresolved',
                confidence: 'unknown',
                relevance: 'unknown',
                reason: 'ambiguous_reference',
                sourceLine: record.lineNumber,
                contentIndex: contentIndex,
                snippet: referenceSnippet(text, identifier)
            ]
            return
        }
        if (matches.size() != 1 || matches.first() == sessionId) {
            return
        }
        String target = matches.first()
        Map evidence = [
            from: sessionId,
            to: target,
            identifier: identifier,
            identifierForm: identifierForm,
            evidenceType: category,
            confidence: confidenceFor(category),
            role: role,
            tool: tool,
            fieldPath: fieldPath,
            sourcePath: record.sourcePath,
            sourceLine: record.lineNumber,
            contentIndex: contentIndex,
            eventId: eventId,
            snippet: referenceSnippet(text, identifier)
        ].findAll { it.value != null }
        evidence.evidenceId = stableId(evidence)
        references[target] << evidence
    }

    List<Map> collectStringLeaves(Object value, String path) {
        List<Map> leaves = []
        if (value instanceof Map) {
            value.each { key, item -> leaves.addAll(collectStringLeaves(item, "${path}.${key}")) }
        } else if (value instanceof Collection) {
            value.eachWithIndex { item, index -> leaves.addAll(collectStringLeaves(item, "${path}[${index}]")) }
        } else if (value != null) {
            leaves << [path: path, value: value.toString()]
        }
        leaves
    }

    Map aggregateReferenceEdge(String rootSession, String from, String to, List<Map> occurrences) {
        List<Map> sorted = occurrences.sort { a, b ->
            int confidenceComparison = (CONFIDENCE_RANK[b.confidence] ?: 0) <=> (CONFIDENCE_RANK[a.confidence] ?: 0)
            if (confidenceComparison != 0) return confidenceComparison
            int identifierComparison = (b.identifierForm == 'full_uuid' ? 1 : 0) <=> (a.identifierForm == 'full_uuid' ? 1 : 0)
            if (identifierComparison != 0) return identifierComparison
            int evidenceComparison = (EVIDENCE_RANK[b.evidenceType] ?: 0) <=> (EVIDENCE_RANK[a.evidenceType] ?: 0)
            if (evidenceComparison != 0) return evidenceComparison
            int lineComparison = (a.sourceLine as int) <=> (b.sourceLine as int)
            if (lineComparison != 0) return lineComparison
            int indexComparison = (a.contentIndex as int) <=> (b.contentIndex as int)
            indexComparison != 0 ? indexComparison : a.evidenceId <=> b.evidenceId
        }
        Map strongest = sorted.first()
        [
            from: from,
            to: to,
            type: 'referenced',
            relationship: from == rootSession ? 'direct' : 'indirect',
            confidence: strongest.confidence,
            strongestEvidenceType: strongest.evidenceType,
            strongestEvidenceId: strongest.evidenceId,
            identifierForm: strongest.identifierForm,
            sourceLine: strongest.sourceLine,
            contentIndex: strongest.contentIndex,
            eventId: strongest.eventId,
            snippet: strongest.snippet,
            evidenceTypes: occurrences.collect { it.evidenceType }.unique().sort(),
            identifierForms: occurrences.collect { it.identifierForm }.unique().sort(),
            evidenceCount: occurrences.size(),
            evidenceIds: occurrences.collect { it.evidenceId }.unique().sort()
        ]
    }

    String confidenceFor(String category) {
        category == 'explicit_session_link' ? 'high' : (category in ['message', 'summary'] ? 'medium' : 'low')
    }

    String weakestConfidence(List<String> values) {
        values.min { CONFIDENCE_RANK[it] ?: 0 } ?: 'low'
    }

    String classifyRelevance(String relationship, String confidence, List<Map> pathEdges) {
        if (relationship == 'root') return 'primary'
        if (relationship == 'direct' && confidence == 'high' && pathEdges.last()?.strongestEvidenceType in ['explicit_session_link', 'message']) return 'primary'
        if (!pathEdges.isEmpty() && pathEdges.every { it.confidence in ['high', 'medium'] }) return 'supporting'
        if (!pathEdges.isEmpty()) return 'incidental'
        'unknown'
    }

    String topicForSession(String sessionId) {
        for (Map record : parseTranscript(sessionId)) {
            Map raw = record.raw as Map
            if (raw.role == 'user' && raw.message instanceof Map && raw.message.content instanceof List) {
                Map text = (raw.message.content as List).find { it instanceof Map && it.type == 'text' } as Map
                if (text?.text) {
                    String topic = text.text.toString().replaceAll('(?s)<timestamp>.*?</timestamp>', ' ').replaceAll('(?s)</?user_query>', ' ').replaceAll('<[^>]+>', ' ').replaceAll('\\s+', ' ').trim()
                    if (topic) return topic.take(160)
                }
            }
        }
        sessionId.take(8)
    }

    String referenceSnippet(String text, String identifier) {
        String normalized = text.replaceAll('\\s+', ' ').trim()
        int index = normalized.toLowerCase().indexOf(identifier.toLowerCase())
        if (index < 0) return normalized.take(180)
        int start = Math.max(0, index - 60)
        int end = Math.min(normalized.length(), index + identifier.length() + 80)
        normalized.substring(start, end)
    }

    List<String> shortestVector(String rootSession, String sessionId, Map<String, String> parents) {
        List<String> vector = []
        String current = sessionId
        while (current != null) {
            vector.add(0, current)
            if (current == rootSession) break
            current = parents[current]
        }
        vector
    }

    List<List<String>> findCycleGroups(List<String> sessions, List<Map> edges) {
        Map<String, Set<String>> adjacency = sessions.collectEntries { [(it): new TreeSet<String>()] }
        edges.each { adjacency[it.from] << it.to }
        Set<String> remaining = new TreeSet<>(sessions)
        List<List<String>> groups = []
        while (!remaining.isEmpty()) {
            String node = remaining.first()
            List<String> component = remaining.findAll { String candidate ->
                reachable(node, candidate, adjacency) && reachable(candidate, node, adjacency)
            }.toList().sort()
            boolean selfCycle = adjacency[node].contains(node)
            if (component.size() > 1 || selfCycle) groups << component
            remaining.removeAll(component)
        }
        groups.sort { a, b -> a.first() <=> b.first() }
    }

    boolean reachable(String source, String target, Map<String, Set<String>> adjacency) {
        if (source == target) return true
        Set<String> visited = [source] as Set
        List<String> queue = [source]
        while (!queue.isEmpty()) {
            String current = queue.remove(0)
            for (String next : adjacency[current] ?: []) {
                if (next == target) return true
                if (visited.add(next)) queue << next
            }
        }
        false
    }

    Map buildReferenceSummary(String rootSession, List<Map> nodes, List<Map> edges, List<List<String>> cycleGroups) {
        Map relevanceCounts = nodes.findAll { it.sessionId != rootSession }.countBy { it.relevance }
        Map confidenceCounts = nodes.findAll { it.sessionId != rootSession }.countBy { it.confidence }
        [
            root: rootSession,
            detected: nodes.size() - 1,
            uniqueReferences: nodes.size() - 1,
            direct: nodes.count { it.baseRelationship == 'direct' },
            directReferences: nodes.count { it.baseRelationship == 'direct' },
            indirect: nodes.count { it.baseRelationship == 'indirect' },
            indirectReferences: nodes.count { it.baseRelationship == 'indirect' },
            edges: edges.size(),
            edgeCount: edges.size(),
            cycles: cycleGroups.size(),
            cycleCount: cycleGroups.size(),
            relevance: new TreeMap(relevanceCounts),
            confidence: new TreeMap(confidenceCounts)
        ]
    }

    void applyReferenceScope(Map graph, String scope) {
        List<Map> nodes = graph.nodes as List<Map>
        Set<String> selected = nodes.findAll { Map node ->
            node.baseRelationship == 'root' ||
                scope == 'recursive' ||
                (scope == 'direct' && node.depth == 1) ||
                (scope == 'relevant' && node.relevance in ['primary', 'supporting'])
        }.collect { it.sessionId } as Set<String>
        if (scope == 'none') selected = [graph.root] as Set<String>
        nodes.each { it.exported = selected.contains(it.sessionId) }
        graph.referenceScope = scope
        graph.exportedSessions = (graph.sessions as List).findAll { selected.contains(it) }
        graph.omittedSessions = (graph.sessions as List).findAll { !selected.contains(it) }
        graph.summary.exported = (graph.exportedSessions as List).size() - 1
        graph.summary.omitted = (graph.omittedSessions as List).size()
    }

    Map exportOneSession(String sessionId, Path destination, boolean referenced, Path existingDestination) {
        secureDirectories(destination)
        String prefix = sessionId.take(8)
        List<Map> records = parseTranscript(sessionId)
        Map normalized = normalizeEvents(sessionId, records)
        Path sessionDir = destination.resolve('session')
        secureDirectories(sessionDir)
        writeJsonLines(sessionDir.resolve("${prefix}-session.jsonl"), normalized.timeline as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-queries.jsonl", normalized.queries as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-responses.jsonl", normalized.responses as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-context.jsonl", normalized.context as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-tool-calls.jsonl", normalized.toolCalls as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-tool-results.jsonl", normalized.toolResults as List)
        writeConditionalJsonLines(sessionDir, "${prefix}-summaries.jsonl", normalized.summaries as List)
        Path rawTranscript = sessionDir.resolve("${prefix}-raw-transcript.jsonl")
        Files.copy(transcriptIndex[sessionId], rawTranscript, StandardCopyOption.REPLACE_EXISTING)
        secureFile(rawTranscript)

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
            schemaVersion: 2,
            exporter: 'cursor-session-exporter.groovy',
            sessionId: sessionId,
            prefix: prefix,
            referenced: referenced,
            referenceModelVersion: 1,
            referenceScope: options.referenceScope,
            sourceTranscript: transcriptIndex[sessionId].toString(),
            exportedAt: Instant.now().toString(),
            security: securityMetadata(destination),
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
        text.contains('<summary_content>') ||
            text.contains('conversation was summarized') ||
            text.startsWith('summary:') ||
            text.startsWith('|') ||
            text.contains('\n|')
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
        secureDirectories(scriptsDir)
        writeJsonLines(scriptsDir.resolve("${prefix}-file-operations.jsonl"), operations)
        writeConditionalJsonLines(scriptsDir, "${prefix}-revisions.jsonl", revisions)
        Path patchesDir = scriptsDir.resolve('patches')
        operations.findAll { it.operation == 'applypatch' }.each { Map operation ->
            secureDirectories(patchesDir)
            String payload = canonicalJson(operation.input)
            Path patchPath = patchesDir.resolve("${operation.eventId}.patch")
            Files.writeString(patchPath, payload, StandardCharsets.UTF_8)
            secureFile(patchPath)
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
                    secureDirectories(target.parent)
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
        secureDirectories(commandDir)
        writeJsonLines(commandDir.resolve("${prefix}-commands.jsonl"), commands)
        writeConditionalJsonLines(commandDir, "${prefix}-command-results.jsonl", results)
        List<Map> copiedLogs = []
        commands.findAll { it.terminalPath }.each { Map command ->
            Path source = Paths.get(command.terminalPath.toString())
            if (Files.isRegularFile(source)) {
                Path relative = Paths.get('commands', 'terminal-logs', source.fileName.toString())
                Path target = destination.resolve(relative)
                secureDirectories(target.parent)
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
                secureDirectories(target.parent)
                copyWithReuse(source, target, existingDestination?.resolve(relative))
                artifacts << [sourcePath: source.toString(), exportPath: relative.toString(), size: Files.size(source), sha256: sha256(source)]
            } else {
                completeness << issue(sessionId, 'artifact_unavailable', rawPath)
            }
        }
        if (!artifacts.isEmpty()) {
            Path artifactsDir = destination.resolve('artifacts')
            secureDirectories(artifactsDir)
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
        secureDirectories(workspaceDir)
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
        List<Map> direct = (activeGraph.nodes ?: []).findAll { it.baseRelationship == 'direct' }
        List<Map> relevant = (activeGraph.nodes ?: []).findAll { it.relevance in ['primary', 'supporting'] && it.sessionId != activeGraph.root }
        String referenceText = relevant.collect { "${(it.shortestVector as List).collect { value -> value.take(8) }.join(' -> ')}: ${it.topic}" }.join('\n')
        String graphPathPrefix = sessionId == activeGraph.root ? '' : '../../'
        String bootstrap = "Restore Cursor session ${sessionId}. Use the supplied manifest, timeline, scripts, commands, artifacts, workspace metadata, and referenced sessions as authoritative evidence. Prioritize primary and supporting references; treat incidental references as forensic evidence only. Do not claim unavailable command results.\n\nRelevant references:\n${referenceText}\n\nContinue from this checkpoint:\n\n${conversation}"
        [
            schemaVersion: 2,
            sessionId: sessionId,
            sourceTranscript: transcriptIndex[sessionId].toString(),
            workspace: workspace,
            requiredFiles: scripts.snapshots ?: [],
            references: [
                modelVersion: 1,
                scope: options.referenceScope,
                completeGraphPath: "${graphPathPrefix}${activeGraph.root.take(8)}-reference-graph.json",
                relevantGraphPath: "${graphPathPrefix}${activeGraph.root.take(8)}-relevant-reference-graph.json",
                evidencePath: "${graphPathPrefix}${activeGraph.root.take(8)}-reference-evidence.jsonl",
                summary: activeGraph.summary,
                direct: direct,
                relevant: relevant
            ],
            checkpoint: checkpoint,
            bootstrapPrompt: bootstrap
        ]
    }

    void writeRootRelationshipFiles(Path staging, String rootSession, Map graph, Map rootResult) {
        String prefix = rootSession.take(8)
        Map completeGraph = new LinkedHashMap(graph)
        List<Map> evidence = completeGraph.remove('evidence') as List<Map>
        writeJson(staging.resolve("${prefix}-reference-graph.json"), completeGraph)
        writeJsonLines(staging.resolve("${prefix}-reference-evidence.jsonl"), evidence)
        writeJson(staging.resolve("${prefix}-reference-summary.json"), graph.summary)
        writeJson(staging.resolve("${prefix}-reference-index.json"), buildReferenceIndex(graph))
        writeJson(staging.resolve("${prefix}-relevant-reference-graph.json"), buildRelevantReferenceGraph(graph))
        Path integrityDir = staging.resolve('integrity')
        secureDirectories(integrityDir)
        List<Map> issues = (completeness + (graph.unresolved as List)).sort { a, b -> canonicalJson(a) <=> canonicalJson(b) }
        writeJson(integrityDir.resolve("${prefix}-missing-items.json"), [items: issues])
        writeJson(integrityDir.resolve("${prefix}-unmatched-results.json"), [items: issues.findAll { it.type == 'command_result_unmatched' }])
        writeJson(integrityDir.resolve("${prefix}-export-report.json"), [
            rootSession: rootSession,
            sessionCount: (graph.sessions as List).size(),
            referenceCount: (graph.sessions as List).size() - 1,
            referenceScope: graph.referenceScope,
            directReferences: graph.summary.direct,
            indirectReferences: graph.summary.indirect,
            exportedReferences: graph.summary.exported,
            omittedReferences: graph.summary.omitted,
            cycleGroups: (graph.cycleGroups as List).size(),
            relevance: graph.summary.relevance,
            completenessIssues: issues.size(),
            reusedArtifacts: reusedArtifacts,
            security: securityMetadata(staging),
            generatedAt: Instant.now().toString()
        ])
    }

    Map buildReferenceIndex(Map graph) {
        List<Map> entries = (graph.nodes as List<Map>).findAll { it.sessionId != graph.root }.collect { Map node ->
            Map edge = (graph.edges as List<Map>).find { it.to == node.sessionId && (node.shortestVector as List).contains(it.from) }
            [
                sessionId: node.sessionId,
                prefix: node.prefix,
                relationship: node.baseRelationship,
                depth: node.depth,
                vector: (node.shortestVector as List).collect { it.take(8) },
                vectorText: (node.shortestVector as List).collect { it.take(8) }.join(' -> '),
                topic: node.topic,
                evidenceType: edge?.strongestEvidenceType,
                confidence: node.confidence,
                relevance: node.relevance,
                cyclic: node.relationship == 'cyclic',
                exported: node.exported
            ]
        }
        [
            root: graph.root,
            scope: graph.referenceScope,
            direct: entries.findAll { it.relationship == 'direct' },
            indirect: entries.findAll { it.relationship == 'indirect' },
            summary: graph.summary
        ]
    }

    Map buildRelevantReferenceGraph(Map graph) {
        Set<String> relevantIds = (graph.nodes as List<Map>).findAll {
            it.sessionId == graph.root || it.relevance in ['primary', 'supporting']
        }.collect { it.sessionId } as Set<String>
        [
            schemaVersion: 2,
            referenceModelVersion: 1,
            root: graph.root,
            sessions: (graph.sessions as List).findAll { relevantIds.contains(it) },
            nodes: (graph.nodes as List).findAll { relevantIds.contains(it.sessionId) },
            edges: (graph.edges as List).findAll { relevantIds.contains(it.from) && relevantIds.contains(it.to) },
            summary: [
                references: relevantIds.size() - 1,
                primary: (graph.nodes as List).count { it.sessionId != graph.root && it.relevance == 'primary' },
                supporting: (graph.nodes as List).count { it.relevance == 'supporting' }
            ]
        ]
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
        secureDirectories(integrityDir)
        writeJson(integrityDir.resolve("${prefix}-checksums.json"), [algorithm: 'SHA-256', files: checksums])
    }

    Map validateBundle(Path root) {
        List<String> errors = []
        if (!Files.isDirectory(root)) {
            return [valid: false, errors: ["bundle not found: ${root}"], checkedFiles: 0]
        }
        errors.addAll(validatePermissions(root))
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
            stream.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith('-reference-graph.json') &&
                    !it.fileName.toString().endsWith('-relevant-reference-graph.json')
            }.forEach { graphFiles << it }
        }
        graphFiles.each { Path graphPath ->
            Map graph = new JsonSlurper().parse(graphPath.toFile()) as Map
            String graphRoot = graph.root?.toString()
            List exportedSessions = graph.exportedSessions instanceof List ? graph.exportedSessions as List : (graph.sessions instanceof List ? graph.sessions as List : [])
            exportedSessions.each { Object rawSession ->
                String sessionId = rawSession.toString()
                Path expected = sessionId == graphRoot ? root : root.resolve('references').resolve(sessionId)
                if (!Files.isDirectory(expected)) {
                    errors << "reference target missing: ${sessionId}"
                }
            }
            (graph.omittedSessions instanceof List ? graph.omittedSessions : []).each { Object rawSession ->
                Path unexpected = root.resolve('references').resolve(rawSession.toString())
                if (Files.exists(unexpected)) {
                    errors << "omitted reference was exported: ${rawSession}"
                }
            }
            (graph.nodes instanceof List ? graph.nodes : []).each { Object rawNode ->
                Map node = rawNode as Map
                ['sessionId', 'relationship', 'baseRelationship', 'depth', 'shortestVector', 'confidence', 'relevance', 'exported'].each { String field ->
                    if (!node.containsKey(field) || node[field] == null) {
                        errors << "reference node missing ${field}: ${node.sessionId}"
                    }
                }
                if (node.shortestVector instanceof List && !(node.shortestVector as List).isEmpty()) {
                    if ((node.shortestVector as List).first() != graphRoot || (node.shortestVector as List).last() != node.sessionId) {
                        errors << "invalid shortest vector: ${node.sessionId}"
                    }
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
        secureFile(target)
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
        secureDirectories(path.parent)
        path.toFile().withWriter(StandardCharsets.UTF_8.name()) { writer ->
            items.each { item ->
                writer.write(canonicalJson(item))
                writer.write('\n')
            }
        }
        secureFile(path)
    }

    void writeJson(Path path, Object value) {
        secureDirectories(path.parent)
        String compact = canonicalJson(value)
        Files.writeString(path, JsonOutput.prettyPrint(compact) + '\n', StandardCharsets.UTF_8)
        secureFile(path)
    }

    void secureDirectories(Path path) {
        if (path == null) {
            return
        }
        List<Path> missing = []
        Path current = path
        while (current != null && !Files.exists(current)) {
            missing << current
            current = current.parent
        }
        missing.reverseEach { Path directory ->
            try {
                if (supportsPosix(directory.parent ?: directory)) {
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
                } else {
                    Files.createDirectory(directory)
                }
            } catch (FileAlreadyExistsException ignored) {
            }
            secureDirectory(directory)
        }
        if (Files.isDirectory(path)) {
            secureDirectory(path)
        }
    }

    void secureDirectory(Path path) {
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS)
        } else {
            setOwnerOnly(path, true)
        }
    }

    void secureFile(Path path) {
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS)
        } else {
            setOwnerOnly(path, false)
        }
    }

    void setOwnerOnly(Path path, boolean directory) {
        File file = path.toFile()
        boolean reset = file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false)
        boolean owner = file.setReadable(true, true) && file.setWritable(true, true)
        boolean executable = !directory || file.setExecutable(true, true)
        if (!reset || !owner || !executable) {
            throw new IOException("unable to enforce owner-only permissions on ${path}")
        }
    }

    boolean supportsPosix(Path path) {
        Path existing = path
        while (existing != null && !Files.exists(existing)) {
            existing = existing.parent
        }
        if (existing == null) {
            return FileSystems.default.supportedFileAttributeViews().contains('posix')
        }
        Files.getFileStore(existing).supportsFileAttributeView('posix')
    }

    void hardenTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                secureDirectory(directory)
                FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                secureFile(file)
                FileVisitResult.CONTINUE
            }
        })
    }

    Map securityMetadata(Path path) {
        [
            enforcement: supportsPosix(path) ? 'posix' : 'owner-only-fallback',
            directoryMode: '0700',
            fileMode: '0600'
        ]
    }

    List<String> validatePermissions(Path root) {
        List<String> errors = []
        if (!supportsPosix(root)) {
            return errors
        }
        Path outputDirectory = root.parent
        if (outputDirectory != null && Files.isDirectory(outputDirectory) && Files.getPosixFilePermissions(outputDirectory) != DIRECTORY_PERMISSIONS) {
            errors << "directory permissions must be 0700: ${outputDirectory}"
        }
        Files.walk(root).withCloseable { stream ->
            stream.sorted().forEach { Path path ->
                if (Files.isDirectory(path)) {
                    if (Files.getPosixFilePermissions(path) != DIRECTORY_PERMISSIONS) {
                        errors << "directory permissions must be 0700: ${path}"
                    }
                } else if (Files.isRegularFile(path) && Files.getPosixFilePermissions(path) != FILE_PERMISSIONS) {
                    errors << "file permissions must be 0600: ${path}"
                }
            }
        }
        errors
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

    static void replacePath(Path source, Path destination) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
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
if (exporterDirectory == null || !Files.exists(exporterDirectory.resolve('cursor-session-exporter.groovy'))) {
    exporterDirectory = Paths.get(System.getProperty('user.dir')).toAbsolutePath().normalize()
}
System.exit(SessionExporter.run(args, exporterDirectory))
