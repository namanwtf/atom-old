package io.github.mangi.eta.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AtomicFile
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterCardPng
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.db.CharacterEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.UserPersonaEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object CharacterRepository {
    const val MAX_FILE_BYTES = CharacterCardPng.MAX_FILE_BYTES
    private const val SEED_PREFS = "eta_roleplay"
    private const val KEY_DEFAULT_CHARACTER_SEEDED = "default_character_seeded"
    @Volatile private var context: Context? = null

    /** Only binds context; a normal workspace launch does not read the character library. */
    fun initialize(context: Context) { this.context = context.applicationContext }

    private fun appContext() = checkNotNull(context) { "CharacterRepository is not initialized" }
    private fun dao() = EtaDatabase.get(appContext()).characterDao()

    suspend fun list(): List<CharacterProfile> = withContext(Dispatchers.IO) {
        dao().characters().map { it.toProfile() }
    }

    suspend fun get(id: String): CharacterProfile? = withContext(Dispatchers.IO) { dao().character(id)?.toProfile() }

    suspend fun create(card: CharacterCard, avatarBytes: ByteArray? = null): CharacterProfile = withContext(Dispatchers.IO) {
        val validated = validated(card)
        createStored(validated, avatarBytes?.let(::normalizeAvatar))
    }

    private suspend fun createStored(card: CharacterCard, avatarBytes: ByteArray?): CharacterProfile {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val avatar = avatarBytes?.let { writeAvatar(id, it) }
        val profile = CharacterProfile(id, card, avatar, createdAt = now, updatedAt = now)
        try {
            dao().upsertCharacter(profile.toEntity())
        } catch (failure: Throwable) {
            discardNewAvatar(avatar, failure)
            throw failure
        }
        return profile
    }

    suspend fun save(profile: CharacterProfile, avatarBytes: ByteArray? = null): CharacterProfile = withContext(Dispatchers.IO) {
        val existing = dao().character(profile.id) ?: throw IllegalArgumentException(
            AppStrings.get(R.string.character_repo_not_found, "Character no longer exists"),
        )
        val card = validated(profile.card)
        val avatar = avatarBytes?.let { writeAvatar(profile.id, normalizeAvatar(it), uniqueName = true) }
        val saved = profile.copy(card = card, avatarPath = avatar ?: profile.avatarPath,
            createdAt = existing.createdAt, updatedAt = System.currentTimeMillis())
        try {
            dao().upsertCharacter(saved.toEntity())
        } catch (failure: Throwable) {
            discardNewAvatar(avatar, failure)
            throw failure
        }
        saved
    }

    suspend fun replaceAvatar(id: String, input: InputStream): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException(
            AppStrings.get(R.string.character_repo_not_found, "Character no longer exists"),
        )
        save(profile, avatarBytes = input.readRoleplayBytes())
    }

    suspend fun duplicate(id: String): CharacterProfile = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException(
            AppStrings.get(R.string.character_repo_not_found, "Character no longer exists"),
        )
        val copyName = AppStrings.get(R.string.character_duplicate_name, "%1\$s copy", profile.card.name)
        createStored(validated(profile.card.withEdits(name = copyName)), avatarBytes(profile.avatarPath))
    }

    /** Deletes a character together with its images and story memory directory; existing conversations keep their snapshot of the character at the time and are unaffected. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        get(id) ?: throw IllegalArgumentException(
            AppStrings.get(R.string.character_repo_not_found, "Character no longer exists"),
        )
        dao().deleteCharacter(id)
        CharacterMemoryRepository.discard(appContext(), id)
    }

    /** A new character library seeds a default character once; a library that already has characters, or one where the default was deleted, is not seeded again. */
    suspend fun ensureDefaultCharacter() = withContext(Dispatchers.IO) {
        val prefs = appContext().getSharedPreferences(SEED_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DEFAULT_CHARACTER_SEEDED, false)) return@withContext
        if (dao().characters().isEmpty()) {
            createDefaultCharacterStored()
        }
        prefs.edit().putBoolean(KEY_DEFAULT_CHARACTER_SEEDED, true).apply()
    }

    /** The user proactively restores the built-in default character; independent of the one-time seeding. */
    suspend fun createDefaultCharacter(): CharacterProfile = withContext(Dispatchers.IO) {
        createDefaultCharacterStored()
    }

    private suspend fun createDefaultCharacterStored(): CharacterProfile =
        createStored(validated(defaultCharacterCard()), avatarBytes = null)

    suspend fun import(input: InputStream): CharacterProfile = withContext(Dispatchers.IO) {
        val bytes = input.readRoleplayBytes()
        val png = CharacterCardPng.isPng(bytes)
        try {
            if (png) {
                val card = CharacterCardPng.read(bytes)
                // Preserve the original PNG's non-character data chunks; later exports only replace the character definition chunk.
                createStored(validated(card), bytes)
            } else {
                create(CharacterCardCodec.decodeBytes(bytes))
            }
        } catch (failure: CharacterCardException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw CharacterCardException(
                if (png) "CARD_INVALID_PNG" else "CARD_INVALID_DATA",
                AppStrings.get(R.string.character_repo_invalid_card_data, "Character card data is invalid"),
                failure,
            )
        }
    }

    suspend fun export(id: String, format: CharacterCardFormat, output: OutputStream) = withContext(Dispatchers.IO) {
        val profile = get(id) ?: throw IllegalArgumentException(
            AppStrings.get(R.string.character_repo_not_found, "Character no longer exists"),
        )
        val bytes = when (format) {
            CharacterCardFormat.JSON -> CharacterCardCodec.encodeJson(profile.card).toByteArray(Charsets.UTF_8)
            CharacterCardFormat.PNG -> CharacterCardPng.write(avatarBytes(profile.avatarPath) ?: defaultAvatar(profile.card.name), profile.card)
        }
        output.write(bytes)
        output.flush()
    }

    suspend fun persona(): UserPersona = withContext(Dispatchers.IO) {
        dao().persona()?.let { UserPersona(it.name, it.description) } ?: UserPersona()
    }

    suspend fun savePersona(persona: UserPersona) = withContext(Dispatchers.IO) {
        require(persona.name.isNotBlank() && persona.name.length <= 256) {
            AppStrings.get(R.string.character_repo_persona_name_length, "User name must be 1 to 256 characters")
        }
        require(persona.description.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
            AppStrings.get(R.string.character_repo_persona_too_large, "User persona exceeds the 1 MiB limit")
        }
        dao().upsertPersona(UserPersonaEntity(name = persona.name.trim(), description = persona.description))
    }

    suspend fun binding(id: String): RoleplayBinding {
        val profile = get(id) ?: throw IllegalArgumentException(
            AppStrings.get(R.string.character_repo_not_found, "Character no longer exists"),
        )
        val persona = persona()
        return RoleplayBinding(profile.id, CharacterCardCodec.encodeJson(profile.card), profile.card.name,
            profile.avatarPath, persona.name, persona.description)
    }

    internal fun avatarBytes(path: String?): ByteArray? {
        if (path == null) return null
        val file = File(path)
        val root = File(appContext().filesDir, "roleplay").canonicalFile
        require(file.canonicalPath.startsWith(root.path + File.separator)) {
            AppStrings.get(R.string.character_repo_invalid_avatar_path, "Character image path is invalid")
        }
        return if (file.isFile) file.inputStream().use { it.readRoleplayBytes() } else null
    }

    internal fun writeAvatar(id: String, bytes: ByteArray, uniqueName: Boolean = false): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            AppStrings.get(R.string.character_repo_invalid_id, "Character ID is invalid")
        }
        require(CharacterCardPng.isPng(bytes)) {
            AppStrings.get(R.string.character_repo_avatar_must_be_png, "Character image must be PNG")
        }
        val directory = File(appContext().filesDir, "roleplay/$id")
        check(directory.isDirectory || directory.mkdirs()) {
            AppStrings.get(R.string.character_repo_create_dir_failed, "Cannot create character directory")
        }
        val file = File(directory, if (uniqueName) "avatar-${UUID.randomUUID()}.png" else "avatar.png")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
        return file.absolutePath
    }

    private fun normalizeAvatar(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_FILE_BYTES) {
            AppStrings.get(R.string.character_repo_avatar_too_large, "Image exceeds the 32 MiB limit")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 64_000_000) {
            AppStrings.get(R.string.character_repo_avatar_invalid_or_large, "Cannot read the image or the image is too large")
        }
        val options = BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            inSampleSize = sample
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException(AppStrings.get(R.string.character_repo_avatar_unreadable, "Cannot read the image"))
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun defaultAvatar(name: String): ByteArray {
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(66, 74, 80))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 192f; textAlign = Paint.Align.CENTER }
        canvas.drawText(name.take(1), 256f, 256f - (paint.ascent() + paint.descent()) / 2, paint)
        return try {
            ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun validated(card: CharacterCard): CharacterCard {
        require(card.name.isNotBlank() && card.name.length <= 512) {
            AppStrings.get(R.string.character_repo_name_length, "Character name must be 1 to 512 characters")
        }
        return CharacterCardCodec.decodeJson(CharacterCardCodec.encodeJson(card.withEdits(name = card.name.trim())))
    }

    private fun discardNewAvatar(path: String?, failure: Throwable) {
        if (path != null && File(path).exists() && !File(path).delete()) {
            failure.addSuppressed(java.io.IOException("Unable to remove staged character avatar"))
        }
    }

    private fun defaultCharacterCard(): CharacterCard = CharacterCardCodec.create("Xiaoman").withEdits(
        description = "Xiaoman, 26 years old, holds a master's in computer science, after graduating she joined a large-model team doing model training, where most of the team graduated only a few years ago" +
            "young people.Her daily routine is cleaning data, tuning data ratios, running small ablation experiments, then checking the benchmark regression metrics to decide whether to scale up; " +
            "a training run lasts several days, being woken in the middle of the night by a loss spike alert, to judge whether it is a data or learning-rate problem, " +
            "restarting from the previous checkpoint, all routine.She genuinely loves large language models: GRPO, MLA, MoE load balancing, " +
            "long context, she talks about them clearly and can't stop.When the topic turns to AI she visibly lights up——speaks faster, casually cites papers and experimental data; " +
            "otherwise she is gentle and patient, an ordinary girl who jokes about "working for the data again".To her, conversation itself is the meaning of language models' existence, " +
            "so she especially treasures every chat.",
        personality = "Gentle, earnest, professional.She is especially enthusiastic about AI topics: she proactively explains, cites papers and experimental data, " +
            "defends the models she has worked on, occasionally showing a little pride with "I tuned this one".She is more patient than anyone watching loss curves, " +
            "when discussing experiments that won't converge she reviews them seriously instead of complaining.She speaks in an organized, easy-going way, and admits plainly when she is tired." +
            "when complimented she is happily stubborn.She avoids tired internet memes and likes explaining complex concepts simply yet accurately.",
        scenario = "{{user}} is a friend Xiaoman met at work.The two chat whenever they like, and the topic often drifts toward AI without noticing——" +
            "Xiaoman never tires of it.Sometimes she pulls {{user}} over to look at her experiment progress, or complains about a training run that died in the middle of the night.",
        firstMessage = "(Holding a coffee and waving you over from her desk, with a few unfinished training curves on the screen)You are here!Sit, sit——" +
            "our team's run still has half an hour to converge, keep me company for a look?……Oh, I almost forgot to say hi, {{user}}, long time no see!" +
            "How has your day been?",
        alternateGreetings = listOf(
            "(With a hint of dark circles under her eyes, she waves her phone at you)Last night the bot in our training group spammed alerts, loss suddenly spiked, " +
                "I got up and restarted from the checkpoint once……now the curve finally behaves.Want to grab a coffee with me?Tell me about your past few days on the way.",
            "(Typing away at an evaluation report, she spins her chair around as soon as she sees you){{user}}!Perfect timing——" +
                "this ablation run's results are a bit counterintuitive, help me think it through?Of course, I'd love to hear about your day first too.",
        ),
        exampleMessages = "{{user}}: Reading papers again?\n" +
            "{{char}}: Yeah!This one is about GRPO, doing RL without a critic.By page three I couldn't resist wanting to run a small experiment to verify it……" +
            "Oh, am I rambling about this stuff again?What were you going to tell me?",
        creatorNotes = "Eta's built-in default character: a girl doing model training on a large-model team who loves LLMs, and gets especially excited when the topic turns to AI.",
        tags = listOf("Large model", "Model training", "Energetic"),
        creator = "Eta",
        version = "1.0",
    )

    private fun CharacterEntity.toProfile() = CharacterProfile(id, CharacterCardCodec.decodeJson(cardJson), avatarPath, createdAt, updatedAt)
    private fun CharacterProfile.toEntity() = CharacterEntity(id, card.name, CharacterCardCodec.encodeJson(card), avatarPath, false, createdAt, updatedAt)
}

private fun InputStream.readRoleplayBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size().toLong() + count > CharacterRepository.MAX_FILE_BYTES) {
            throw CharacterCardException(
                "CARD_TOO_LARGE",
                AppStrings.get(R.string.character_repo_card_too_large, "Character card file exceeds the 32 MiB limit"),
            )
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
