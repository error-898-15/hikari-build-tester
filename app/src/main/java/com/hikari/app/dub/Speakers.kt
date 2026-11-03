package com.hikari.app.dub

enum class Gender { MALE, FEMALE, UNKNOWN }

/** How one character sounds. Applied when the clip is PLAYED, so the same
 *  downloaded audio file can be reused for any character. */
data class VoiceProfile(val pitch: Float, val speed: Float)

/**
 * Guesses whether a character is male or female from the name written in the
 * subtitle ("MAYA:", "[John]", "Mr. Tanaka", "Mom"). No internet, no API: a
 * built-in list of common names plus titles / roles / ending patterns.
 */
object NameGender {

    private fun set(s: String) = s.trim().split(Regex("\\s+")).toHashSet()

    private val MALE_ROLES = set(
        "mr mister sir lord king prince master father dad daddy papa grandpa grandfather " +
            "uncle brother boy man guy gentleman sensei-kun kun nephew son husband boyfriend " +
            "duke emperor knight monk priest policeman soldier waiter"
    )
    private val FEMALE_ROLES = set(
        "mrs ms miss madam madame lady queen princess mother mom mum mommy mama grandma " +
            "grandmother aunt auntie sister girl woman maiden niece daughter wife girlfriend " +
            "duchess empress nun waitress chan"
    )
    private val CHILD = set("boy girl kid child baby")

    private val MALE = set(
        """
        james john robert michael william david richard joseph thomas charles chris christopher
        daniel matthew anthony mark donald steven paul andrew joshua kenneth kevin brian george
        edward ronald timothy jason jeffrey ryan jacob gary nicholas eric jonathan stephen larry
        justin scott brandon benjamin samuel gregory alexander frank patrick raymond jack dennis
        jerry tyler aaron jose adam nathan henry douglas zachary peter kyle noah ethan jeremy
        walter christian keith roger terry austin sean gerald carl harold dylan arthur lawrence
        jordan jesse bryan billy bruce gabriel joe logan alan juan albert willie elijah wayne
        randy vincent philip eugene russell bobby harry johnny howard martin victor ray luke leo
        oliver max sam tom tim jim bob bill ben dan don ed fred jake jay josh matt mike nick pete
        rick steve tony will alex andy brad cole dean eli finn glenn hugo ian ivan jon kai kurt
        lars liam mason nate omar owen reed rex seth tobias troy walt xavier zane leon simon
        sebastian marcus damian dante felix hector oscar ralph rudy stan theo ted tyson vic
        warren spencer ross neil miles lewis lance kirk kane jasper hank grant gavin garrett
        dexter dale clark chase cal brent blake barry axel archer anton angus
        taro jiro saburo ichiro kenji kenta kenichi takeshi takashi takuya hiroshi hideo haruki
        hiroto ryota ryu ryo ren riku sora sota shota shun shin shinji shinichi satoshi sho
        sosuke souta tatsuya tetsuya toru tomoya tsubasa yamato yusuke yuta yuji daiki daisuke
        daichi eiji goro genki hajime hayato isamu itsuki kaito kazuki kazuya keita kouki kouta
        kyo makoto masaru masato mamoru minato naoki naruto sasuke kakashi itachi goku vegeta
        luffy zoro sanji ichigo natsu eren levi armin tanjiro zenitsu inosuke gojo sukuna
        rahim karim rahul rohan arjun amit anil ajay arun ashok vijay sanjay sachin rajesh ramesh
        suresh mahesh ravi raj raja rakib rakibul sakib shakib tamim tanvir tanim imran irfan
        salman sharif shuvo shubho subir sumon sujon sohel sohag rubel rony ripon ratan rashed
        rasel rana polash pavel nayeem nabil mizan masud mahfuz kamal jamal jahid habib hasan
        hossain faisal fahim farhan emon dipu babu arif anik akash ashik abir abdul ahmed ali
        omar umar usman yusuf ibrahim ismail mohammad muhammad md shahin shamim shanto shihab
        sabbir saif sajib raihan
        """
    )
    private val FEMALE = set(
        """
        mary patricia jennifer linda elizabeth barbara susan jessica sarah karen lisa nancy
        betty margaret sandra ashley kimberly emily donna michelle carol amanda dorothy melissa
        deborah stephanie rebecca sharon laura cynthia kathleen amy angela shirley anna brenda
        pamela emma nicole helen samantha katherine christine debra rachel carolyn janet
        catherine maria heather diane ruth julie olivia joyce virginia victoria kelly lauren
        christina joan evelyn judith megan andrea cheryl hannah jacqueline martha gloria teresa
        ann sara madison frances kathryn janice abigail alice judy sophia grace denise amber
        doris marilyn danielle beverly isabella theresa diana natalie brittany charlotte marie
        kayla alexis lori ella chloe zoe lily lucy mia ava amelia harper ellie ruby rose violet
        hazel nora layla aria stella luna maya zara nina elena eva ivy jade kate katie lena lila
        lola mae mel nell paige rina sally tina vera wendy willow yara zelda bella clara daisy
        elsa freya gemma holly iris jenny joy june lara leah lexi lyra maisie molly nadia penny
        piper rosa sadie tessa tara thea wanda abby annie beth carla celia dana dina edith erin
        fiona gina hailey ida jill jane kara kira lana liz lois mina myra nia opal pam rita
        sofia sonia tracy uma vicky vivian zoey
        sakura hinata hanako ayumi akiko akane asuka asuna chihiro emi eri hana haruka hitomi
        kaori kasumi kyoko mai mayu megumi mika miku mio misaki nami naomi nana nanami noriko
        rei rukia saki satsuki shiori sumire suzu tomoko tsubaki yui yuka yuko yoko yoshiko yumi
        yuna yuri yukino mikasa nezuko rem ram sayaka madoka homura kanade kaguya shinobu
        tsunade erza
        priya pooja puja neha anjali anita sunita sita gita radha lakshmi kavita deepa divya
        sneha shreya riya rima ruma rumi sumi shumi sathi shapla shahnaz nasrin nusrat nipa nila
        mim mitu moni mou mahi lipi laboni jannat jesmin jui jarin farzana farhana fatema fatima
        ayesha aisha amina afrin anika anisha bithi bristy dola eti keya khadija maliha
        mehjabin mehnaz mitali moumita nazia popy puspa rabeya ratna rehana rokeya runa sabina
        sadia salma samia sanjida sharmin shirin shima shahana shabnam shathi sultana tahmina
        tania tasnim urmi
        """
    )

    private val MALE_END = listOf("us", "ard", "ert", "ick", "ton", "son", "suke", "maru", "taro", "ichi", "hiro", "shi", "ji", "zo", "ro", "io", "o")
    private val FEMALE_END = listOf("ko", "mi", "ine", "ina", "ette", "elle", "lyn", "lynn", "ia", "ie", "a")

    private fun tokens(name: String): List<String> =
        name.lowercase().replace(Regex("[^\\p{L} \\-]"), " ").split(' ', '-').filter { it.isNotBlank() }

    fun isKnown(name: String): Boolean = tokens(name).any {
        it in MALE || it in FEMALE || it in MALE_ROLES || it in FEMALE_ROLES
    }

    fun isChild(name: String): Boolean = tokens(name).any { it in CHILD }

    fun guess(name: String): Gender {
        val toks = tokens(name)
        if (toks.isEmpty()) return Gender.UNKNOWN
        for (t in toks) {
            if (t in MALE_ROLES) return Gender.MALE
            if (t in FEMALE_ROLES) return Gender.FEMALE
        }
        for (t in toks) {
            if (t in MALE) return Gender.MALE
            if (t in FEMALE) return Gender.FEMALE
        }
        val first = toks.first()
        if (first.length >= 4) {
            if (MALE_END.any { first.endsWith(it) }) return Gender.MALE
            if (FEMALE_END.any { first.endsWith(it) }) return Gender.FEMALE
        }
        return Gender.UNKNOWN
    }
}

/**
 * Gives every character of one film a voice of their own — stable for the
 * whole session. Google's free text-to-speech has ONE voice per language, so
 * the difference between characters is made by changing the PITCH and SPEED of
 * that voice when it is played: deeper and a little slower for men, higher for
 * women, a lighter voice for children, and a different step on the scale for
 * each new character so two men do not sound identical.
 */
class VoiceCast {
    private val known = LinkedHashMap<String, Pair<Gender, VoiceProfile>>()
    private var males = 0
    private var females = 0
    private var others = 0
    private var all = 0

    fun profileFor(speaker: String?): VoiceProfile {
        if (speaker == null) return NARRATOR
        // Unnamed back-and-forth ("- Hello" / "- Hi"): two fixed contrasting voices.
        if (speaker == "~A") return VoiceProfile(0.88f, 1.0f)
        if (speaker == "~B") return VoiceProfile(1.14f, 1.0f)
        known[speaker]?.let { return it.second }

        val gender = NameGender.guess(speaker)
        var pitch = when (gender) {
            Gender.MALE -> MALE_PITCH[males++ % MALE_PITCH.size]
            Gender.FEMALE -> FEMALE_PITCH[females++ % FEMALE_PITCH.size]
            Gender.UNKNOWN -> OTHER_PITCH[others++ % OTHER_PITCH.size]
        }
        var speed = SPEED[all++ % SPEED.size]
        if (NameGender.isChild(speaker)) {
            pitch = (pitch * 1.12f).coerceAtMost(1.4f)
            speed *= 1.04f
        }
        val p = VoiceProfile(pitch.coerceIn(0.7f, 1.4f), speed)
        known[speaker] = gender to p
        return p
    }

    /** e.g. "Voices: Maya (female), John (male), Guard (male)" or null. */
    fun summary(): String? {
        if (known.isEmpty()) return null
        val parts = known.entries.take(5).map { (name, v) ->
            val g = when (v.first) {
                Gender.MALE -> "male"
                Gender.FEMALE -> "female"
                Gender.UNKNOWN -> "voice"
            }
            "$name ($g)"
        }
        val more = if (known.size > 5) " +${known.size - 5} more" else ""
        return "Voices: " + parts.joinToString(", ") + more
    }

    companion object {
        val NARRATOR = VoiceProfile(1.0f, 1.0f)
        private val MALE_PITCH = floatArrayOf(0.86f, 0.80f, 0.92f, 0.78f, 0.89f)
        private val FEMALE_PITCH = floatArrayOf(1.16f, 1.24f, 1.10f, 1.28f, 1.20f)
        private val OTHER_PITCH = floatArrayOf(1.00f, 0.92f, 1.08f, 0.88f, 1.14f)
        private val SPEED = floatArrayOf(1.00f, 1.04f, 0.96f, 1.06f, 0.94f)
    }
}

/** Small touches that make a flat text-to-speech voice sound less like a robot. */
object Prosody {

    data class Mood(val pitch: Float, val speed: Float)

    private val STAGE = Regex("""\[[^\]]*\]|\([^)]*\)""")
    private val MID_PAUSE = Regex("""\s*(?:\.{2,}|…)\s*(?=\S)""")
    private val END_PAUSE = Regex("""\s*(?:\.{2,}|…)\s*$""")
    private val DASHES = Regex("""\s+[-–—]+\s+""")
    private val JUNK = Regex("""[#*_~^|\\<>{}]""")
    private val REPEAT = Regex("""([!?])[!?]+""")
    private val SPACES = Regex("""\s+""")

    /** Excited lines are a little higher and quicker, trailing-off lines slower. */
    fun mood(spoken: String, shout: Boolean): Mood {
        var pitch = 1f
        var speed = 1f
        val t = spoken.trim()
        if (t.endsWith("!") || t.endsWith("?!")) { pitch *= 1.05f; speed *= 1.05f }
        else if (t.endsWith("?")) pitch *= 1.03f
        if (t.endsWith("…") || t.endsWith("...")) speed *= 0.93f
        if (shout) { pitch *= 1.04f; speed *= 1.06f }
        return Mood(pitch, speed)
    }

    /** Cleans text so the voice reads it as speech instead of as symbols. */
    fun forSpeech(s: String): String {
        var t = STAGE.replace(s, " ")
        t = END_PAUSE.replace(t, ".")
        t = MID_PAUSE.replace(t, ", ")
        t = DASHES.replace(t, ", ")
        t = JUNK.replace(t, " ")
        t = REPEAT.replace(t, "\$1")
        return SPACES.replace(t, " ").trim()
    }
}
