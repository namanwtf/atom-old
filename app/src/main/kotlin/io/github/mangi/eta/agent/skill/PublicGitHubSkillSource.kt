package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class GitHubSkillRepository(
    val owner: String,
    val repository: String,
    val ref: String? = null,
    val path: String? = null,
) {
    val slug: String = "$owner/$repository"
}

internal data class GitHubSkillCandidate(
    val name: String,
    val path: String,
)

internal data class GitHubSkillInspection(
    val repository: String,
    val ref: String,
    val commitSha: String,
    val prefix: String?,
    val candidates: List<GitHubSkillCandidate>,
)

internal class GitHubSkillSourceException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Parses a user-provided GitHub repository or tree/blob URL, rejecting mirrors, credentials, and custom ports. */
internal object GitHubSkillRepositoryParser {
    fun parse(value: String): GitHubSkillRepository {
        val input = value.trim()
        if (input.isBlank()) {
            invalid(AppStrings.get(R.string.skill_i18n_github_repo_empty, "GitHub repository cannot be empty"))
        }
        if (input.length > MAX_SOURCE_LENGTH) {
            invalid(AppStrings.get(R.string.skill_i18n_github_repo_too_long, "GitHub repository URL is too long"))
        }
        return if (input.contains("://")) parseUrl(input) else parseSlug(input)
    }

    fun resolve(
        repository: String,
        explicitRef: String?,
        explicitPath: String?,
    ): GitHubSkillRepository {
        val parsed = parse(repository)
        val normalizedRef = explicitRef?.trim()?.takeIf { it.isNotEmpty() }?.let(::normalizeRef)
        val normalizedPath = explicitPath?.let(::normalizeRelativePath)
        if (parsed.ref != null && normalizedRef != null && parsed.ref != normalizedRef) {
            invalid(AppStrings.get(R.string.skill_i18n_github_ref_mismatch, "The ref in the URL does not match the ref parameter"))
        }
        if (parsed.path != null && normalizedPath != null && parsed.path != normalizedPath) {
            invalid(AppStrings.get(R.string.skill_i18n_github_path_mismatch, "The path in the URL does not match the path parameter"))
        }
        return parsed.copy(
            ref = normalizedRef ?: parsed.ref,
            path = normalizedPath ?: parsed.path,
        )
    }

    fun normalizeRelativePath(value: String): String {
        val normalized = value.trim()
        if (normalized == ".") return "."
        if (
            normalized.isBlank() ||
            normalized.length > MAX_PATH_LENGTH ||
            normalized.startsWith('/') ||
            '\u0000' in normalized ||
            '\\' in normalized ||
            normalized.any { it.isISOControl() }
        ) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_path_must_be_relative,
                    "The Skill path must be a relative path within the repository",
                ),
            )
        }
        val segments = normalized.trimEnd('/').split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_path_invalid_segment,
                    "The Skill path contains an invalid segment",
                ),
            )
        }
        return segments.joinToString("/")
    }

    fun normalizeRef(value: String): String {
        val normalized = value.trim()
        val segments = normalized.split('/')
        if (
            !REF_PATTERN.matches(normalized) ||
            normalized.startsWith('/') ||
            normalized.endsWith('/') ||
            "//" in normalized ||
            "@{" in normalized ||
            segments.any { it == "." || it == ".." || it.endsWith(".lock") }
        ) {
            invalid(AppStrings.get(R.string.skill_i18n_github_invalid_ref, "GitHub ref is invalid"))
        }
        return normalized
    }

    private fun parseUrl(input: String): GitHubSkillRepository {
        val uri = runCatching { URI(input) }
            .getOrElse {
                invalid(AppStrings.get(R.string.skill_i18n_github_invalid_url, "GitHub URL is invalid"))
            }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            invalid(AppStrings.get(R.string.skill_i18n_github_https_only, "Only HTTPS GitHub URLs are supported"))
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host !in ALLOWED_GITHUB_HOSTS || uri.rawUserInfo != null || uri.port != -1) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_public_host_only,
                    "Only github.com public repository URLs are supported",
                ),
            )
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_url_no_query_or_fragment,
                    "GitHub URL must not contain query parameters or fragments",
                ),
            )
        }
        if ("//" in uri.path.orEmpty() || '\\' in uri.path.orEmpty()) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_invalid_url_path,
                    "GitHub URL path is invalid",
                ),
            )
        }
        val segments = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (segments.size < 2) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_url_missing_owner_repo,
                    "GitHub URL is missing owner/repository",
                ),
            )
        }
        val owner = validateOwner(segments[0])
        val repository = validateRepository(segments[1].removeSuffix(".git"))
        if (segments.size == 2) return GitHubSkillRepository(owner, repository)
        if (segments[2] !in setOf("tree", "blob") || segments.size < 4) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_url_type_unsupported,
                    "Only GitHub repository, tree or blob URLs are supported",
                ),
            )
        }
        val ref = normalizeRef(segments[3])
        var path = segments.drop(4).joinToString("/").takeIf { it.isNotBlank() }
            ?.let(::normalizeRelativePath)
        if (segments[2] == "blob" && path?.substringAfterLast('/') == SKILL_FILE_NAME) {
            path = path.substringBeforeLast('/', missingDelimiterValue = ".")
        }
        return GitHubSkillRepository(owner, repository, ref, path)
    }

    private fun parseSlug(input: String): GitHubSkillRepository {
        val segments = input.removeSuffix(".git").split('/')
        if (segments.size != 2) {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_invalid_slug,
                    "The repository should be owner/repository or a github.com URL",
                ),
            )
        }
        return GitHubSkillRepository(
            owner = validateOwner(segments[0]),
            repository = validateRepository(segments[1]),
        )
    }

    private fun validateOwner(value: String): String {
        if (!OWNER_PATTERN.matches(value)) {
            invalid(AppStrings.get(R.string.skill_i18n_github_invalid_owner, "GitHub owner is invalid"))
        }
        return value
    }

    private fun validateRepository(value: String): String {
        if (!REPOSITORY_PATTERN.matches(value) || value == "." || value == "..") {
            invalid(
                AppStrings.get(
                    R.string.skill_i18n_github_invalid_repository,
                    "GitHub repository is invalid",
                ),
            )
        }
        return value
    }

    private fun invalid(message: String): Nothing =
        throw GitHubSkillSourceException("INVALID_GITHUB_SOURCE", message)

    private const val SKILL_FILE_NAME = "SKILL.md"
    private const val MAX_SOURCE_LENGTH = 2_048
    private const val MAX_PATH_LENGTH = 1_000
    private val ALLOWED_GITHUB_HOSTS = setOf("github.com", "www.github.com")
    private val OWNER_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
    private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]{1,100}")
    private val REF_PATTERN = Regex("[A-Za-z0-9._/-]{1,200}")
}

/**
 * Public GitHub Skill source.
 *
 * Candidates are discovered via the GitHub API tree; the install archive is pinned to a commit SHA and downloaded from codeload.
 * The client sends no Token, does not follow redirects, and applies hard limits to JSON and ZIP responses separately.
 */
internal class PublicGitHubSkillSource(
    cacheRoot: File,
    baseClient: OkHttpClient,
) : Closeable {
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val cacheDirectory = File(cacheRoot, "skill-github")
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean(false)

    fun listCurated(): GitHubSkillInspection = inspect(
        GitHubSkillRepository(
            owner = CURATED_OWNER,
            repository = CURATED_REPOSITORY,
            ref = CURATED_REF,
            path = CURATED_PATH,
        ),
    )

    fun inspect(repository: GitHubSkillRepository): GitHubSkillInspection {
        ensureOpen()
        val ref = repository.ref ?: resolveDefaultBranch(repository)
        val commit = resolveCommit(repository, ref)
        val tree = getJson(
            apiUrl(
                "repos",
                repository.owner,
                repository.repository,
                "git",
                "trees",
                commit.treeSha,
                query = "recursive" to "1",
            ),
            MAX_TREE_RESPONSE_BYTES,
        )
        if (tree.optBoolean("truncated", false)) {
            throw GitHubSkillSourceException(
                "REPOSITORY_TREE_TOO_LARGE",
                AppStrings.get(
                    R.string.skill_i18n_github_tree_too_large,
                    "The repository directory is too large, GitHub did not return complete results; please provide an explicit Skill path",
                ),
            )
        }
        val prefix = repository.path?.takeUnless { it == "." }?.trimEnd('/')
        val candidates = buildList {
            val entries = tree.optJSONArray("tree") ?: return@buildList
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if (entry.optString("type") != "blob") continue
                val skillFilePath = entry.optString("path")
                if (skillFilePath.substringAfterLast('/') != SKILL_FILE_NAME) continue
                val root = runCatching {
                    GitHubSkillRepositoryParser.normalizeRelativePath(
                        skillFilePath.substringBeforeLast('/', missingDelimiterValue = "."),
                    )
                }.getOrNull() ?: continue
                if (prefix != null && root != prefix && !root.startsWith("$prefix/")) continue
                add(
                    GitHubSkillCandidate(
                        name = root.substringAfterLast('/').takeUnless { root == "." }
                            ?: repository.repository,
                        path = root,
                    ),
                )
                if (size > MAX_CANDIDATES) {
                    throw GitHubSkillSourceException(
                        "TOO_MANY_SKILL_CANDIDATES",
                        AppStrings.get(
                            R.string.skill_i18n_github_too_many_candidates,
                            "Candidate Skills exceed %1\$d, please narrow the repository path",
                            MAX_CANDIDATES,
                        ),
                    )
                }
            }
        }.sortedBy { it.path }
        return GitHubSkillInspection(
            repository = repository.slug,
            ref = ref,
            commitSha = commit.sha,
            prefix = prefix,
            candidates = candidates,
        )
    }

    fun downloadArchive(repository: GitHubSkillRepository): DownloadedGitHubArchive {
        ensureOpen()
        val ref = repository.ref ?: resolveDefaultBranch(repository)
        val resolvedCommitSha = resolveCommit(repository, ref).sha
        if (
            COMMIT_SHA_PATTERN.matches(ref) &&
            !resolvedCommitSha.equals(ref, ignoreCase = true)
        ) {
            throw GitHubSkillSourceException(
                "GITHUB_COMMIT_MISMATCH",
                AppStrings.get(
                    R.string.skill_i18n_github_commit_mismatch,
                    "The commit returned by GitHub does not match the inspected version",
                ),
            )
        }
        val commitSha = ref.takeIf(COMMIT_SHA_PATTERN::matches) ?: resolvedCommitSha
        ensureSafeCacheDirectory()
        cleanupStaleArchives()
        val target = File.createTempFile("eta-skill-github-", ".zip", cacheDirectory)
        return try {
            val url = codeloadUrl(repository, commitSha)
            downloadTo(url, target, MAX_ARCHIVE_BYTES)
            DownloadedGitHubArchive(
                file = target,
                repository = repository.slug,
                ref = ref,
                commitSha = commitSha,
            )
        } catch (throwable: Throwable) {
            target.delete()
            throw throwable
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
    }

    private fun resolveDefaultBranch(repository: GitHubSkillRepository): String {
        val metadata = getJson(
            apiUrl("repos", repository.owner, repository.repository),
            MAX_METADATA_RESPONSE_BYTES,
        )
        return metadata.optString("default_branch").trim()
            .takeIf { it.isNotBlank() }
            ?.let(GitHubSkillRepositoryParser::normalizeRef)
            ?: throw GitHubSkillSourceException(
                "INVALID_GITHUB_RESPONSE",
                AppStrings.get(
                    R.string.skill_i18n_github_missing_default_branch,
                    "GitHub did not return a default branch",
                ),
            )
    }

    private fun resolveCommit(repository: GitHubSkillRepository, ref: String): CommitPointer {
        val commit = getJson(
            apiUrl("repos", repository.owner, repository.repository, "commits", ref),
            MAX_METADATA_RESPONSE_BYTES,
        )
        val sha = commit.optString("sha").takeIf { COMMIT_SHA_PATTERN.matches(it) }
        val treeSha = commit.optJSONObject("commit")
            ?.optJSONObject("tree")
            ?.optString("sha")
            ?.takeIf { COMMIT_SHA_PATTERN.matches(it) }
        if (sha == null || treeSha == null) {
            throw GitHubSkillSourceException(
                "INVALID_GITHUB_RESPONSE",
                AppStrings.get(
                    R.string.skill_i18n_github_invalid_commit_sha,
                    "GitHub did not return a valid commit/tree SHA",
                ),
            )
        }
        return CommitPointer(sha = sha, treeSha = treeSha)
    }

    private fun getJson(url: HttpUrl, maxBytes: Long): JSONObject {
        val bytes = execute(url) { response ->
            val contentLength = response.body.contentLength()
            if (contentLength > maxBytes) responseTooLarge()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val output = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) responseTooLarge()
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throwable ->
                throw GitHubSkillSourceException(
                    "INVALID_GITHUB_RESPONSE",
                    AppStrings.get(
                        R.string.skill_i18n_github_invalid_json,
                        "GitHub returned invalid JSON",
                    ),
                    throwable,
                )
            }
    }

    private fun downloadTo(url: HttpUrl, target: File, maxBytes: Long) {
        execute(url, accept = "application/zip") { response ->
            val contentLength = response.body.contentLength()
            if (contentLength > maxBytes) archiveTooLarge()
            response.body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) archiveTooLarge()
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun <T> execute(
        url: HttpUrl,
        accept: String = "application/vnd.github+json",
        block: (okhttp3.Response) -> T,
    ): T {
        ensureOpen()
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .build()
        val call = client.newCall(request)
        activeCalls += call
        if (closed.get()) call.cancel()
        return try {
            call.execute().use { response ->
                if (response.isRedirect) {
                    throw GitHubSkillSourceException(
                        "GITHUB_REDIRECT_REJECTED",
                        AppStrings.get(
                            R.string.skill_i18n_github_redirect_rejected,
                            "The GitHub download was redirected, access to other hosts was rejected",
                        ),
                    )
                }
                if (!response.isSuccessful) {
                    val code = when (response.code) {
                        403, 429 -> "GITHUB_RATE_LIMITED"
                        404 -> "GITHUB_NOT_FOUND"
                        else -> "GITHUB_REQUEST_FAILED"
                    }
                    val message = when (code) {
                        "GITHUB_RATE_LIMITED" -> AppStrings.get(
                            R.string.skill_i18n_github_rate_limited,
                            "The GitHub request is rate-limited, please retry later",
                        )
                        "GITHUB_NOT_FOUND" -> AppStrings.get(
                            R.string.skill_i18n_github_not_found,
                            "Public GitHub repository, ref or path not found",
                        )
                        else -> AppStrings.get(
                            R.string.skill_i18n_github_request_failed,
                            "GitHub request failed(HTTP %1\$d)",
                            response.code,
                        )
                    }
                    throw GitHubSkillSourceException(code, message)
                }
                block(response)
            }
        } catch (failure: GitHubSkillSourceException) {
            throw failure
        } catch (failure: IOException) {
            val isCancelled = closed.get()
            throw GitHubSkillSourceException(
                code = if (isCancelled) "GITHUB_REQUEST_CANCELLED" else "GITHUB_NETWORK_ERROR",
                message = if (isCancelled) {
                    AppStrings.get(
                        R.string.skill_i18n_github_request_cancelled,
                        "GitHub request was cancelled",
                    )
                } else {
                    AppStrings.get(
                        R.string.skill_i18n_github_network_error,
                        "Unable to access the public GitHub repository",
                    )
                },
                cause = failure,
            )
        } finally {
            activeCalls -= call
        }
    }

    private fun apiUrl(
        vararg segments: String,
        query: Pair<String, String>? = null,
    ): HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host(API_HOST)
        .apply { segments.forEach(::addPathSegment) }
        .apply { query?.let { addQueryParameter(it.first, it.second) } }
        .build()

    private fun codeloadUrl(repository: GitHubSkillRepository, commitSha: String): HttpUrl =
        HttpUrl.Builder()
            .scheme("https")
            .host(CODELOAD_HOST)
            .addPathSegment(repository.owner)
            .addPathSegment(repository.repository)
            .addPathSegment("zip")
            .addPathSegment(commitSha)
            .build()

    private fun ensureOpen() {
        if (closed.get()) {
            throw GitHubSkillSourceException(
                "GITHUB_REQUEST_CANCELLED",
                AppStrings.get(
                    R.string.skill_i18n_github_request_cancelled,
                    "GitHub request was cancelled",
                ),
            )
        }
    }

    private fun ensureSafeCacheDirectory() {
        val path = cacheDirectory.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw GitHubSkillSourceException(
                    "CACHE_UNAVAILABLE",
                    AppStrings.get(
                        R.string.skill_i18n_cache_dir_unsafe,
                        "Skill download cache directory is unsafe",
                    ),
                )
            }
            return
        }
        if (!cacheDirectory.mkdirs() || Files.isSymbolicLink(path)) {
            throw GitHubSkillSourceException(
                "CACHE_UNAVAILABLE",
                AppStrings.get(
                    R.string.skill_i18n_cache_dir_create_failed,
                    "Unable to create the Skill download cache",
                ),
            )
        }
    }

    private fun cleanupStaleArchives(nowMillis: Long = System.currentTimeMillis()) {
        cacheDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith("eta-skill-github-") &&
                    file.name.endsWith(".zip") &&
                    nowMillis - file.lastModified() > STALE_ARCHIVE_AGE_MILLIS
            }
            .forEach { it.delete() }
    }

    private fun responseTooLarge(): Nothing =
        throw GitHubSkillSourceException(
            "GITHUB_RESPONSE_TOO_LARGE",
            AppStrings.get(
                R.string.skill_i18n_github_response_too_large,
                "GitHub response exceeds the safe limit",
            ),
        )

    private fun archiveTooLarge(): Nothing =
        throw GitHubSkillSourceException(
            "GITHUB_ARCHIVE_TOO_LARGE",
            AppStrings.get(
                R.string.skill_i18n_github_archive_too_large,
                "GitHub repository archive exceeds the %1\$d MiB limit",
                MAX_ARCHIVE_BYTES / 1024 / 1024,
            ),
        )

    internal data class DownloadedGitHubArchive(
        val file: File,
        val repository: String,
        val ref: String,
        val commitSha: String,
    ) : Closeable {
        override fun close() {
            file.delete()
        }
    }

    private data class CommitPointer(
        val sha: String,
        val treeSha: String,
    )

    private companion object {
        const val CURATED_OWNER = "openai"
        const val CURATED_REPOSITORY = "skills"
        const val CURATED_REF = "main"
        const val CURATED_PATH = "skills/.curated"
        const val SKILL_FILE_NAME = "SKILL.md"
        const val API_HOST = "api.github.com"
        const val CODELOAD_HOST = "codeload.github.com"
        const val USER_AGENT = "Atom-Skill-Installer"
        const val GITHUB_API_VERSION = "2022-11-28"
        const val MAX_CANDIDATES = 200
        const val MAX_METADATA_RESPONSE_BYTES = 1L * 1024 * 1024
        const val MAX_TREE_RESPONSE_BYTES = 8L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
        const val STALE_ARCHIVE_AGE_MILLIS = 24L * 60 * 60 * 1_000
        val COMMIT_SHA_PATTERN = Regex("[a-fA-F0-9]{40,64}")
    }
}
