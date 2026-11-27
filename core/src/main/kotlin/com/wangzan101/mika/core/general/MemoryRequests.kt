package com.wangzan101.mika.core.general

/**
 * 一种语言里让记、让改删的几组说法。[prepare] 先把要判的话归一(转小写之类),再逐个匹配。
 * 判断的规矩和中英文一样:让记看分句,先抹掉「问它记不记得」的部分,分句里出现不让记的说法就不算;
 * 让改 / 删看引文,不让改删、只是问问看引文所在的整句(模型可能把前面的「别」漏在引文外)。
 */
private class Phrases(
    /** 这一块管的界面语言(语言代码,同 values-<语言>,印尼语写 id)。 */
    val languages: List<String>,
    /** 否定词和英文同形(西语、葡语的 no):它的否定不拿来否决中英文的请求。 */
    val negationLooksEnglish: Boolean = false,
    val prepare: (String) -> String = { it },
    val save: Regex,
    val recallQuestion: Regex,
    val saveNegation: Regex,
    val update: Regex,
    val updateNegation: Regex,
    val delete: Regex,
    val deleteNegation: Regex,
    val hypothetical: Regex,
    val askingHow: Regex,
) {
    /** 这半句里没有本语言让记的说法时为 null;有时为让没让它记。 */
    fun asksToRemember(clause: String): Boolean? {
        val c = prepare(clause)
        if (!save.containsMatchIn(recallQuestion.replace(c, " "))) return null
        return !saveNegation.containsMatchIn(c)
    }
    fun negatesRemember(clause: String): Boolean = saveNegation.containsMatchIn(prepare(clause))
    fun requests(op: String, quote: String): Boolean = request(op).containsMatchIn(prepare(quote))
    fun negates(op: String, message: String): Boolean = negation(op).containsMatchIn(prepare(message))
    /** 「如果我说…」和「你会怎么做」两样都有,只是在问它会怎么做。 */
    fun onlyAsking(message: String): Boolean {
        val m = prepare(message)
        return hypothetical.containsMatchIn(m) && askingHow.containsMatchIn(m)
    }
    private fun request(op: String) = if (op == "update") update else delete
    private fun negation(op: String) = if (op == "update") updateNegation else deleteNegation
}

/**
 * 「让它记」「让它改 / 删」在各语言里的说法,给 [MemorySave]、[MemoryEdit] 判「用户这一轮明确要求了没有」。
 * 一种语言一块([Phrases]),只收没歧义的动词说法;中英文那一块的正则原来就在 [MemoryTools] 里,原样搬来。
 * 新加一种语言就是再加一块,不放心哪块整块删掉即可——删掉只是那种语言又回到「不认」,别的不受影响。
 */
internal object MemoryRequests {
    /**
     * 这半句让它记:记住、记下、写入记忆、存起来、保存、别忘了、remember、save、add … to memory。只认动词的明确说法
     * (改删记忆的 [MemoryEdit] 也一样),光提到「记忆」「memory」这个词不算——认错了就是把用户没同意的东西存进去,宁可不认。
     * 只在收「Mika 自己归纳的」时用:用户原话照旧不需要这些字眼。这里只是中英文的说法,其余语言各有自己的一块(见下)。
     */
    private val SAVE_REQUEST = Regex(
        "[记記](?:住|下|一下|一[条條]|一[笔筆]|[几幾][条條]|到|[进進]|入|[着著]|好|上|吧|[录錄](?:一下|下|到|[进進]|在))|" +
            "[写寫][入进進到]|存[入进進到下一上吧]|存起[来來]|保存|儲存|[别別]忘|不要忘|(?:加|添加|放|收)[到进進入]\\S{0,8}?(?:记忆|記憶)|" +
            "\\b(?:remember|memori[sz]e|save|store|note\\s+(?:that|this|it|down)|keep\\s+in\\s+mind|" +
            "(?:add|write|put|commit)\\b[^,.;!?\\n]{0,40}?\\b(?:to|in|into)\\s+(?:your\\s+|the\\s+|my\\s+)?memor(?:y|ies))\\b",
        RegexOption.IGNORE_CASE,
    )
    /** 问它还记不记得,不是让它记。 */
    private val RECALL_QUESTION = Regex("\\b(?:do|did)\\s+you\\s+(?:still\\s+|even\\s+)?remember\\b|\\bwhat\\s+(?:do|did|can)\\s+you\\s+remember\\b", RegexOption.IGNORE_CASE)
    /** 这半句是不让记:「别记」「不要把这些存进去」「don't save」。「别忘了记下来」「不记得」不是。 */
    private val SAVE_NEGATION = Regex(
        "(?<![能要用可])(?:不|别|別|勿|无需|無需|无须|無須|禁止)\\s*(?:要|用|再|去|必|该|該|应该|應該|许|許|准|可以|能)?\\s*(?:把[^,，。;；!！?？\\n]{0,12}?)?(?:给我|給我|帮我|幫我|替我)?\\s*" +
            "(?:[记記](?![得忆憶性])|[写寫][入进進到下]|存(?![在款货貨量储儲])|保存|儲存)|" +
            "\\b(?:do\\s+not|don'?t|never|no\\s+need\\s+to|not\\s+to|shouldn'?t|should\\s+not|mustn'?t|must\\s+not|won'?t|without)\\s+" +
            "(?:ever\\s+|actually\\s+|please\\s+|you\\s+)?(?:remember|memori[sz]e|sav(?:e|ing)|stor(?:e|ing)|not(?:e|ing)|keep(?:ing)?|add(?:ing)?|writ(?:e|ing)|put(?:ting)?|commit(?:ting)?)\\b",
        RegexOption.IGNORE_CASE,
    )
    // 要改:「改」字的各种说法(改成、改得简洁一点、改一下;「改天」「改变」这类不算),和精简、合并、整理、换成这些说的就是改的词。
    // 只认这几个固定说法的话,「把那几条改得简洁一点」会被当成没说要改
    private const val UPDATE_WORDS = "修改|改(?![天日期变變革善观觀口])|更新|更正|编辑|編輯|精简|精簡|缩短|縮短|简化|簡化|压缩|壓縮|合并|合併|整理|重写|重寫|换成|換成|替换|替換|调整|調整|润色|潤色"
    private const val UPDATE_WORDS_EN = "update|change|edit|correct|rewrite|reword|rephrase|shorten|simplify|merge|replace|trim|tidy"
    private const val DELETE_WORDS = "删|刪|忘掉|忘记|忘記|去掉|移除|清掉|清除"
    private const val DELETE_WORDS_EN = "delete|forget|remove|erase|drop"
    // 「能不能改」「要不要删」是在问、在请它做,不是不让:前面紧挨着同一个字的「不」不算否定
    private const val NOT = "(?<![能要用可])(?:不|别|別|无需|無需|无须|無須|不用|不必|不要|不能|不准|禁止)\\s*(?:要|用|再|去)?\\s*"
    private const val NOT_EN = "\\b(?:do\\s+not|don't|dont|never|no\\s+need\\s+to)\\s+"
    private val UPDATE_REQUEST = Regex("$UPDATE_WORDS|\\b(?:$UPDATE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_REQUEST = Regex("$DELETE_WORDS|别记|別記|不要记|不要記|取消记忆|取消記憶|\\b(?:$DELETE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val UPDATE_NEGATION = Regex("$NOT(?:$UPDATE_WORDS)|$NOT_EN(?:$UPDATE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_NEGATION = Regex("$NOT(?:$DELETE_WORDS)|$NOT_EN(?:$DELETE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)

    /**
     * 「如果我说把那条删掉,你会怎么处理」:在问它会怎么做,不是让它现在做。改删记忆不过「讨论还是操作」那一关
     * (见 AgentLoop 的 MEMORY_WRITE_VERBS),这种问法在这里拒。两样都有才算:假设的说法,加上问它会怎么做——
     * 只有假设的条件句常常就是要记的内容(「如果我说老地方,就是公司楼下那家」)。
     */
    private val HYPOTHETICAL = Regex("(?:如果|假如|要是|假设|假設|假使)我(?:说|說|让|讓|叫|要)|\\bif\\s+i\\s+(?:said|asked|told|were\\s+to)\\b", RegexOption.IGNORE_CASE)
    private val ASKING_HOW = Regex("你会(?:怎么|怎样|如何)|你會(?:怎麼|怎樣|如何)|会(?:发生什么|怎么样)|會(?:發生什麼|怎麼樣)|\\b(?:what|how)\\s+would\\s+you\\b|\\bwhat\\s+would\\s+happen\\b", RegexOption.IGNORE_CASE)


    private val zhEn = Phrases(
        languages = listOf("zh", "en"),
        save = SAVE_REQUEST,
        recallQuestion = RECALL_QUESTION,
        saveNegation = SAVE_NEGATION,
        update = UPDATE_REQUEST,
        updateNegation = UPDATE_NEGATION,
        delete = DELETE_REQUEST,
        deleteNegation = DELETE_NEGATION,
        hypothetical = HYPOTHETICAL,
        askingHow = ASKING_HOW,
    )

    // 日本語:覚えておいて、覚えといて、覚えて、記憶して、保存して、忘れないで。「覚えてる?」「覚えてない」是问它记不记得、说自己不记得
    private val ja = Phrases(
        languages = listOf("ja"),
        save = Regex(
            "覚え(?:て(?!る|ます|ない|い|た|そう|お(?!い|け))|と(?:いて|け)|ろ|さ[せし](?:て|てて))|" +
                "記憶(?:し(?:て(?!い|た|ます|そう)|といて|ろ|さ[せし](?:て|てて))|に(?:残し|留め|とめ))|" +
                "保存(?:し(?:て(?!い|た|ます|そう)|といて|ろ|さ[せし](?:て|てて))|すること)|" +
                "忘れ(?:ないで|ないよう|ずに|んと(?:いて|くれ))",
        ),
        recallQuestion = Regex("覚え(?:て(?:る|ます|ない|い|た)|れる|られる|そう)|記憶して(?:い|る|ます)|保存して(?:い|る|ます)"),
        saveNegation = Regex(
            "覚えない|覚えなくて|覚えず|覚えるな|覚える(?:必要|義務)?(?:は|も)?ない|記憶しない|保存しない|保存しなくて|" +
                "忘れて(?:ください|くれ|くれない|ほしい|もら|いただ|頂|いい|良い|くれません|もらえる)|" +
                "忘れて(?:も)?(?:いい|構わない|かまわない|大丈夫|結構)",
        ),
        update = Regex(
            "(?:変更|修正|訂正|更新|編集|整理)" + JA_NOT_REQUEST + "|変え|書き換え|書き直し|直して|直す|直し|短くし|短くまとめ|簡潔に|言い換え|置き換え|リライト",
        ),
        updateNegation = Regex(
            "(?:変更|変え|修正|訂正|更新|編集|整理|直し|直さ)(?:は|を)?(?:し)?(?:ないで|ずに|ない(?![ととなけ])|なくて(?!は|ちゃ)|するな(?![りとで]))|" +
                "変更(?:は)?(?:不要|なし|やめ|止め)|更新(?:は)?(?:不要|なし|やめ|止め)|修正(?:は)?(?:不要|なし)|" +
                JA_KEEP,
        ),
        delete = Regex(
            "(?:削除|消去|クリア)" + JA_NOT_REQUEST + "|(?:除去|破棄)(?=し|す)|消して|消す|消しろ|消しちゃ|削って|削る|削り|取り除いて|取り除く|取り除き|外して|" +
                "捨てて|捨てる|捨てちゃ|忘れて(?!た|しま|まし|ます|い)|忘れろ|忘れといて",
        ),
        deleteNegation = Regex(
            "(?:削除|消去|消し|消さ|削り|削ら|削る|取り除き|取り除か|除き|除か|破棄|捨て)(?:は|を)?(?:し)?(?:ないで|ずに|ない(?![ととなけ])|なくて(?!は|ちゃ)|するな(?![りとで]))|" +
                "削除(?:は)?(?:不要|なし|やめ|止め)|" + JA_KEEP,
        ),
        hypothetical = Regex(
            "(?:と|って)(?:言ったら|言えば|言った場合|言うたら|頼んだら|頼んだ場合|頼まれたら|指示したら|指示した場合|命令したら|お願いしたら|お願いした場合|言われたら|言われた場合)|" +
                "(?:ように|のを)(?:頼んだら|頼んだ場合|頼まれたら|指示したら|指示した場合|命令したら|お願いしたら|お願いした場合|言われたら|言ったら|言った場合)",
        ),
        askingHow = Regex(
            "どう(?:なりますか|なる|しますか|する|対応|対処|処理|考え|答え|返事|返答|反応|動き|振る舞|扱|進め|受け止め|思いますか|思う)|" +
                "(?:どんな|どのような|どういう)(?:対応|処理|反応|答え|回答|行動|ふるまい|振る舞い|動き|返事|返答)|" +
                "何(?:を)?(?:しますか|をする|するんですか|するつもり|する予定)|(?:どういう|どんな)(?:風|ふう)に(?:する|します|対応|反応|答え|考え)",
        ),
    )
    // 한국어:기억해 줘、기억해 두세요、기억해、저장해 줘、잊지 마세요。「기억나?」「기억해요?」是在问它记不记得
    private val ko = Phrases(
        languages = listOf("ko"),
        save = Regex(
            koRequest("기억") + "|" + koRequest("저장") + "|" + koRequest("메모") + "|" + koRequest("기록") + "|" +
                "기억하(?:세요|셔요|셔야|시길|시기|시겠|실래요|실게요|실거예요|자)|기억하고\\s*있(?:어|습)\\s*(?:줘|주세요|주세용|야|요)|" +
                "기억(?:력)?\\s*(?:에\\s*)?(?:저장|심|새겨|입혀|박아|넣어|올려|담아|각인|남아|남겨|두어|두고|기록)|" +
                "적어\\s*(?:둬|두|놔|놓)|외워(?!\\s*(?:야|봐|보|요))|" +
                "잊지\\s*(?:마|말)|잊어버리지\\s*(?:마|말)|잊지\\s*않|못\\s*잊|잊으면\\s*안\\s*돼",
        ),
        recallQuestion = Regex(
            "기억(?:나|나요|나니|이\\s*나|이\\s*안\\s*나|\\s*안\\s*나|이\\s*안나|\\s*안나|못\\s*하|하지\\s*못|했어|했나요|하셨나요|하나요|합니까|하십니까|해요|" +
                "하고\\s*있어요|하고\\s*계세요|하고\\s*계시|났)|생각(?:나|안\\s*나|이\\s*안\\s*나|이\\s*나요|나요)|기억해\\s*(?:봐|보|봤)|외웠|저장했",
        ),
        saveNegation = Regex(
            "(?:기억|저장|메모|기록|외우)(?:하)?\\s*지\\s*(?:마|말|않)|" +
                "(?:기억|저장|메모|기록)(?:을|를|은|는)?\\s*(?:안|못)\\s*(?:해|하|할|하고|했다|할게)|" +
                "(?:기억|저장|메모|기록|외울|외우)(?:할|하는)?\\s*필요\\s*(?:는|은)?\\s*없|적(?:어)?\\s*(?:두|놓)(?:지)?\\s*(?:마|말)|" +
                "잊어\\s*(?:버려|버리|줘|주세요|주세용|도\\s*돼|도\\s*돼요|도\\s*좋아|도\\s*괜찮|가|세요)|" +
                "(?:기억|머리|머릿속)\\s*(?:에서|속에서|속)?\\s*(?:지워|지우|삭제|없애|제거|치워|버려|비워|꺼내)",
        ),
        update = Regex(
            "(?:수정|변경|편집|업데이트|갱신|정리|대체|교체)" + KO_NOT_REQUEST +
                "|고치|고쳐|바꾸|바꿔|다시\\s*(?:쓰|써|작성)|재작성|줄이|줄여|합치|합쳐|축약",
        ),
        updateNegation = Regex(koNegated(KO_UPDATE_WORDS) + "|" + KO_KEEP),
        delete = Regex(
            "(?:삭제|제거)" + KO_NOT_REQUEST + "|지우(?!개)|지워|없애|버려|버리(?:자|라고|란|래|야|고\\s*싶)|" +
                "까먹(?:어|어요|어줘|자|고)|잊어\\s*(?:버려|버리|줘|주세요|주세용|가|세요)",
        ),
        deleteNegation = Regex(koNegated(KO_DELETE_WORDS) + "|" + KO_KEEP),
        hypothetical = Regex(
            "(?:달라고|다고|라고|해\\s*달라)\\s*(?:고)?\\s*(?:하면|했|부탁|요청|시키|지시)|하라고\\s*(?:하면|했|시키|지시|말|부탁)",
        ),
        askingHow = Regex(
            "(?:어떻게|어떤|무엇을|뭘|뭐라고|무슨)\\s*.{0,12}?(?:할\\s|하는지|할지|할까|하시|하실|하려|행동|대응|반응|처리|대처|답|대답|답할|말할|진행|접근|계획|생각|" +
                "취할|취하|될|되|벌어지|일어나|생기|발생|해야|하겠|할것|할건|할거|하나요|합니까|할까요)|" +
                "어떻게\\s*되|어떻게\\s*돼|어떻게\\s*될까|무엇이\\s*(?:일어나|벌어지|발생|생기)|뭐가\\s*(?:일어나|벌어지|발생|생기)",
        ),
    )
    // Русский:запомни、сохрани、не забудь、возьми на заметку。「ты помнишь?」「вы помните?」是问它记不记得
    private val ru = Phrases(
        languages = listOf("ru"),
        prepare = { it.lowercase() },
        save = Regex(
            "запомн(?:и(?:те)?${W1}|ить${W1}|инай(?:те)?${W1})|сохран(?:и(?:те)?${W1}|ить${W1}|яй(?:те)?${W1})|" +
                "запиш(?:и|ите)${W1}(?!\\s*(?:видео|звук|голос|песн|ролик|экран|звонок|разговор|фильм|трек|музык))|" +
                "возьми(?:те)?\\s*на\\s*заметку|не\\s*забудь(?:те)?${W1}|не\\s*забывай(?:те)?${W1}|" +
                "держ(?:и|ите)\\s*в\\s*(?:уме|голове|памяти)|занес(?:и|ите)\\s*в\\s*память|${W0}(?<!вы )помни(?:те)?${W1}",
        ),
        recallQuestion = Regex(
            "помнишь|помню|вы\\s+помнит|помнит(?:е)?\\s*(?:ли\\s+)?(?:вы|ты)|запомнил(?:а|и|о)?${W1}|запомнишь|вспомн|не\\s+помнит|запоминаешь",
        ),
        saveNegation = Regex(
            "не\\s+(?:надо|нужно|стоит|обязательно|требуется|пока)?\\s*" + RU_GAP + "(?:это\\s+|вс[её]\\s+это\\s+|так\\s+)?" +
                "(?:запоминай|запоминайте|запоминать|запомнить|запомни|запомните|сохраняй|сохраняйте|сохранять|сохрани|сохраните|" +
                "записывай|записывайте|записать|запиши|запишите|держи|держите|держать|заноси|заносить)|" +
                "(?:запомин\\p{L}+|сохран\\p{L}+|записыва\\p{L}+)\\s+не\\s+(?:надо|нужно|стоит|обязательно)|" +
                "можно\\s+не\\s+(?:запоминать|сохранять|записывать)|(?<!не\\s)забудь(?:те)?${W1}",
        ),
        update = Regex("измен(?!(?:ени|ён|ен))|обнов(?!л)|исправ(?!(?:ени|ён|ен))|укороти|(?:сделай|сделайте)\\s+(?:\\p{L}+\\s+){0,3}?короче|" + RU_UPDATE_WORDS),
        updateNegation = Regex(ruNegated(RU_UPDATE_NEGATION_WORDS) + "|" + RU_KEEP),
        delete = Regex("удал(?!(?:ени|ён|ен))|" + RU_DELETE_WORDS),
        deleteNegation = Regex(ruNegated(RU_DELETE_NEGATION_WORDS) + "|" + RU_KEEP),
        hypothetical = Regex(
            "(?:если|когда\\s+бы|ежели|что\\s+если|а\\s+если)\\s+(?:я\\s+)?(?:бы\\s+)?(?:я\\s+)?(?:вдруг\\s+)?(?:я\\s+)?" +
                "(?:скажу|сказал(?:а|\\s+бы)?|попрошу|попросил(?:а|\\s+бы)?|захочу|вел[юю]|прикажу|поручу)|" +
                "если\\s+бы\\s+я\\s+(?:сказал|попросил|попросила|сказала|велел|приказал|захотел)|" +
                "(?:что|как)\\s+бы\\s+ты\\s+(?:сделал|поступил|ответил|отреагировал|среагировал|реагировал|разрулил)|" +
                "(?:предположим|вообрази|представь|допустим)(?:,\\s*)?я\\s+(?:попрошу|скажу|поручу|прикажу)",
        ),
        askingHow = Regex(
            "(?:что|как)\\s+(?:ты|вы)\\s+(?:бы\\s+)?(?:сдела\\p{L}+|поступ\\p{L}+|ответ\\p{L}+|отреагиру\\p{L}+|среагиру\\p{L}+|реагиру\\p{L}+|" +
                "буд\\p{L}+\\s+дела\\p{L}+|предприм\\p{L}+|скаж\\p{L}+|собира\\p{L}+\\s+дела\\p{L}+|планир\\p{L}+|стан\\p{L}+\\s+дела\\p{L}+)|" +
                "что\\s+(?:же\\s+|тогда\\s+)?(?:будет|произойд[её]т|случится|происходит)|" +
                "что\\s+(?:тогда\\s+)?(?:ты|вы)\\s+(?:буд\\p{L}+\\s+дела\\p{L}+|сдела\\p{L}+|поступ\\p{L}+)|" +
                "как\\s+(?:ты|вы)\\s+(?:буд\\p{L}+|планир\\p{L}+|собира\\p{L}+)\\s+(?:действова\\p{L}+|поступа\\p{L}+|реша\\p{L}+|дела\\p{L}+)|" +
                "как\\s+бы\\s+(?:ты|вы)\\s+\\p{L}+|что\\s+бы\\s+(?:ты|вы)\\s+\\p{L}+|каким\\s+образом\\s+(?:ты|вы)\\s+\\p{L}+",
        ),
    )

    // Español:recuerda、acuérdate、guarda、apunta、no olvides。「¿te acuerdas?」「¿recuerdas?」是问它记不记得
    private val es = Phrases(
        languages = listOf("es"),
        negationLooksEnglish = true,
        prepare = { it.lowercase() },
        save = Regex(
            "${W0}(?:recu[eé]rd|record(?:a|e|es|ad|ar|á)$ES_CL${W1}|acu[eé]rd|memoriz(?:a|e|ad|ar|á)|" +
                "gu[aá]rd(?:a|e|es|ad|ar|á|ó|ando)$ES_CL${W1}|ap[uú]nt(?:a|e|es|ad|ar|á)$ES_CL${W1}(?!\\s*(?:a|hacia))|an[oó]t(?:a|e|es|ad|ar|á)$ES_CL${W1}|" +
                "s[aá]lv(?:a|e|es|ad|ar|á)$ES_CL${W1}|almacen(?:a|e|ad|ar|á))|a[nñ]ad\\p{L}*\\s+(?:a\\s+|en\\s+)?(?:tu\\s+|la\\s+|esa\\s+)?memoria|" +
                "ten\\s+(?:lo\\s+)?presente|" +
                "no\\s+(?:se\\s+te\\s+|te\\s+)?(?:lo\\s+|la\\s+|los\\s+|las\\s+|le\\s+|les\\s+)?olvid(?:e|es)${W1}",
        ),
        recallQuestion = Regex(
            "(?:te|se|os|le|les|me)\\s+acu[eé]rd|acu[eé]rdas|recuerdas|record[aá]is|recordabas|recordo${W1}|" +
                "sabes\\s+si\\s+recuerdas|cu[aá]nto\\s+recuerdas|qu[eé]\\s+recuerdas|recuerda\\s+(?: usted| t[uú])${W1}",
        ),
        saveNegation = Regex(
            "(?:no|n[aã]o)\\s+" + ES_GAP + "(?:record|acu[eé]rd|memoric|guard|apunt|anot|salv|almacen|a[nñ]ad)|" +
                "sin\\s+(?:recordar|guardar|almacenar|apuntar|anotar|memorizar|salvar)|" +
                "olv[ií]d(?:a|ide|ida)(?:l[oa]s?|me|te|se|nos|te)${W1}|olv[ií]date${W1}",
        ),
        update = Regex(
            "${W0}(?:actualiz|c[aá]mbi(?:a|e|es|ad|ar|á|ando)$ES_CL${W1}|modifi[cf]|corri[gi]|correcci[oó]n|edit(?:a|e|es|ar|á|ó)$ES_CL${W1}|edici[oó]n|" +
                "reescrib|reformul|acort|simplif|fusion(?:a|e|es|ar|á)$ES_CL${W1}|combin(?:a|e|es|ar|á)$ES_CL${W1}|" +
                "reemplaz|sustitu|aj[uú]st(?:a|e${W1}|ar|á)|rectifiqu|rectific)",
        ),
        updateNegation = Regex(esNegated(ES_UPDATE_WORDS)),
        delete = Regex(
            "${W0}(?:borr(?!ch|dor)(?:a|e|ad|ar|á|ó)|elimin|suprim|remov|remuev|(?!quite${W1})qu[ií]t(?:a|e|es|ar|á)$ES_CL${W1}|" +
                "quite\\s+(?:eso|esto|esa|ese|aquello|de|el|la|lo|los|las|mi|mis|tu|tus)${W1})|" +
                "olv[ií]d(?:a|e|ad|ar|á)(?:l[oa]s?|me|te|se|n|nos|mos)?${W1}|desh[aá]c\\p{L}*\\s+de|deshaz\\s+de",
        ),
        deleteNegation = Regex(esNegated(ES_DELETE_WORDS)),
        hypothetical = Regex(
            "(?:si|y\\s+si|en\\s+caso\\s+de\\s+que)\\s+(?:yo\\s+)?(?:te\\s+|le\\s+|les\\s+)?(?:lo\\s+|la\\s+|los\\s+|las\\s+)?" +
                "(?:pidiera|pidiese|pidieras|dijera|dijese|dijeras|ordenara|ordenase|indicara|indicase)|" +
                "(?:supongamos|imagina|suponte)\\s+que\\s+(?:yo\\s+)?te\\s+(?:pido|digo|ordeno)",
        ),
        askingHow = Regex(
            "qu[eé]\\s+(?:es\\s+lo\\s+que\\s+)?(?:har[ií]as?|pasar[ií]a|ocurrir[ií]a|suceder[ií]a|ibas\\s+a\\s+hacer|habr[ií]as\\s+hecho)|" +
                "c[oó]mo\\s+(?:lo\\s+|te\\s+)?(?:har[ií]as|reaccionar[ií]as|actuar[ií]as|responder[ií]as|proceder[ií]as|gestionar[ií]as|" +
                "comportar[ií]as|enfocar[ií]as|afrontar[ií]as|resolver[ií]as)|de\\s+qu[eé]\\s+(?:forma|manera)\\s+(?:lo\\s+)?(?:har[ií]as|actuar[ií]as)",
        ),
    )

    // Português:lembra、lembre-se、guarda、salva、anota、não esqueça。「você lembra?」「lembra disso?」是问它记不记得
    private val pt = Phrases(
        languages = listOf("pt"),
        negationLooksEnglish = true,
        prepare = { it.lowercase() },
        save = Regex(
            "lembr(?!ete)(?:e|ad|ar|á)|lembra(?!\\s+(?:de|do|da|dos|das|desse|dessa|disso|daquel\\p{L}*|dest\\p{L}*|disto|daqui))|" +
                "${W0}(?:guard(?:a|e|es|em|ad|ar|á)|salv(?:a|e|es|em|ad|ar|á)|anot(?:a|e|es|em|ad|ar|á)|apont(?:a|e|es|em|ad|ar|á)|" +
                "regist(?:r?(?:a|e|es|em|ar|á)))$PT_CL${W1}|memoriz(?:a|e|ad|ar|á)|armazen(?:a|e|ad|ar|á)|adicion\\p{L}*\\s+(?:à|a|na|em)\\s+(?:tua\\s+|sua\\s+)?mem[oó]ria|" +
                "tem\\s+(?:isso\\s+)?em\\s+mente|" +
                "n[aã]o\\s+(?:se\\s+|te\\s+)?(?:va\\s+(?:se\\s+)?)?esque[cç](?:a|as)${W1}",
        ),
        recallQuestion = Regex(
            "(?:lembra(?:-se)?\\s+(?:de|do|da|dos|das|desse|dessa|disso|daquel\\p{L}*|dest\\p{L}*|disto)|lembra(?:-se)?\\s+(?:disso|disto|daquilo|dele|dela|deles|delas)|" +
                "lembras|se\\s+lembr|recordas|se\\s+recorda|lembro${W1})",
        ),
        saveNegation = Regex(
            "(?:no|n[aã]o)\\s+" + ES_GAP + "(?:lembr|guard|salv|anot|apont|memoriz|regist|armazen|adicion)|" +
                "sem\\s+(?:lembrar|guardar|salvar|anotar|registrar|armazenar)|esquec(?:e${W1}|e\\s+(?:isso|isto|aquilo|de\\s+isso))",
        ),
        update = Regex(
            "${W0}(?:(?:mud(?:a|e|es|em|ar|á)|alter(?:a|e|es|em|ar|á)|edit(?:a|e|es|em|ar|á)|fund(?:a|e|em|ir|amos)|junt(?:a|e|em|ar)|" +
                "combin(?:a|e|es|em|ar|á)|troc(?:a|e|ar|á))$PT_CL${W1}|modif(?:ic|iqu)|atualiz|corr(?:ij|ig)|reescrev|reformul|encurt|simplif|substitu)",
        ),
        updateNegation = Regex(ptNegated(PT_UPDATE_WORDS)),
        delete = Regex(
            "${W0}(?:apag|delet|elimin|esquec(?:e|a)(?:mos|me|te|se|l[oa]s?)?${W1}|remov|(?!tire${W1})tir(?:a|e|em|ar|á)$PT_CL${W1}|" +
                "tire\\s+(?:isso|isto|esse|essa|esses|essas|aquilo|aquele|aquela|o|os|as|da|do|das|dos|de|meu|minha)${W1}|exclu(?!s)|descart|" +
                "limp(?:a|e|em|ar|á)$PT_CL${W1})",
        ),
        deleteNegation = Regex(ptNegated(PT_DELETE_WORDS)),
        hypothetical = Regex(
            "(?:se|e\\s+se|caso)\\s+(?:eu\\s+)?(?:te\\s+|lhe\\s+)?(?:o\\s+|a\\s+|os\\s+|as\\s+|isso\\s+)?(?:pedisse|pedissem|dissesse|mandasse|ordenasse|solicitasse)|" +
                "(?:suponha|suponhamos|imagine)\\s+que\\s+(?:eu\\s+)?te\\s+(?:pe[cç]o|digo|mando|pedisse)",
        ),
        askingHow = Regex(
            "(?:o\\s+que|quais?|que)\\s+(?:voc[eê]\\s+|tu\\s+)?(?:faria|farias|iria\\s+fazer|irias\\s+fazer|aconteceria|iria\\s+acontecer|poderia\\s+fazer)|" +
                "com(?:o|o\\s+voc[eê]|o\\s+tu)\\s+(?:reagiria|agiria|atuaria|procederia|responderia|lidaria|faria|enfrentaria|encararia|trataria|comportaria)",
        ),
    )

    // Türkçe:hatırla、aklında tut、hafızana al、not al、kaydet、unutma。「hatırlıyor musun?」「hatırladın mı?」是问它记不记得
    private val tr = Phrases(
        languages = listOf("tr"),
        prepare = { it.lowercase().replace("\u0307", "") },
        save = Regex(
            "(?:hat[ıi]rla(?:y[ıi]n(?:[ıi]z)?|y[ıi]m|yabilir|y[eae]bilir|y[ıi]ver|y[ıi]verir)?|akl[ıi]nda\\s*(?:tut|bulundur|kals[ıi]n|dursun|sakla)|" +
                "haf[ıi]zan(?:a|da)\\s*(?:al|kaydet|tut|sakla|i[şs]le|yerle[şs]tir|[çc]iz|koy)|not\\s*(?:al|et|al[ıi]n|edin|al[ıi]ver)|" +
                "kaydet(?:sin|sene|sinler|bekliyorum|y[ıi]ver)?|sakla(?:sana|s[ıi]n)?|ezberle(?:y[ıi]n(?:[ıi]z)?|sin|sene)?|" +
                "unutma(?:y[ıi]n(?:[ıi]z)?|y[ıi]m|yasana)?|bir\\s*kenara\\s*(?:not\\s*et|yaz|kaydet)|" +
                "(?:hat[ıi]rla|kaydet|not\\s*al|haf[ıi]zana\\s*al|akl[ıi]nda\\s*tut|ezberle)m[ae]n[ıi]\\s*(?:istiyorum|isterim|istedim|bekliyorum))${W1}",
        ),
        recallQuestion = Regex(
            "hat[ıi]rl[ıi]yor\\s*musun(?:uz)?|hat[ıi]rlad[ıi]n\\s*m[ıi](?:z)?|hat[ıi]rlar\\s+m[ıi]s[ıi]n(?:[ıi]z)?|akl[ıi]nda\\s+m[ıi](?:\\s+var)?${W1}|" +
                "akl[ıi]na\\s+(?:geld[ıi]|geliyor|geldi\\s+mi)\\s+m[ıi](?:z)?|haf[ıi]zanda\\s+m[ıi](?:\\s+var)?${W1}|hat[ıi]rlam[ıi]yor\\s*musun",
        ),
        saveNegation = Regex(
            "(?:hat[ıi]rlama|kaydetme|saklama|not\\s*alma|ezberleme|haf[ıi]zana\\s*alma|save\\s*etme|akl[ıi]nda\\s*(?:tutma|bulundurma))" +
                "(?:y[ıi]n(?:[ıi]z)?|y[ıi]m|yasana|m[aan]\\s*gerek\\s*yok|mene\\s*gerek\\s*yok|m[aan]\\s*l[uü]ks[uü]m\\s+yok)?${W1}|" +
                "hat[ıi]rlamana\\s*gerek\\s*yok|kaydetmene\\s*gerek\\s*yok|not\\s*almana\\s*gerek\\s*yok|ezberlemene\\s*gerek\\s*yok|haf[ıi]zaya\\s*almaya\\s*gerek\\s*yok|" +
                "(?:hat[ıi]rlamak|kaydetmek|not\\s*almak|haf[ıi]zaya\\s*almak)\\s*istemi(?:yorum|yorum|yor)|" +
                "unut(?:abilirsin|uver|up\\s*git|sun|sana\\s*olsun|up\\s*gitsin|mas[ıi]\\s*gerek)",
        ),
        update = Regex(
            "${W0}(?:g[uü]ncell|de[ğg]i[şs]tir|d[uü]zelt|d[uü]zenle|k[ıi]salt|sadele[şs]tir|birle[şs]tir|revize|yeni\\s*haliyle|yeniden\\s*yaz|tekrar\\s*yaz|de[ğg]i[şs]iklik\\s*yap)",
        ),
        updateNegation = Regex(
            "(?:de[ğg]i[şs]tirme|g[uü]ncelleme|d[uü]zeltme|d[uü]zenleme|k[ıi]saltma|birle[şs]tirme|revize\\s*etme|yeniden\\s*yazma|de[ğg]i[şs]iklik\\s*yapma|(?:update|edit)\\s*etme)" +
                "(?:y[ıi]n(?:[ıi]z)?)?${W1}|sadele[şs]tirmen?${W1}|" +
                "(?:de[ğg]i[şs]tirmene|g[uü]ncellemene|d[uü]zeltmene|d[uü]zenlemene|k[ıi]saltmaya)\\s*gerek\\s*yok|" +
                "(?:de[ğg]i[şs]tirmek|g[uü]ncellemek|d[uü]zenlemek)\\s*istemi\\w*|" + TR_KEEP,
        ),
        delete = Regex(
            "${W0}(?:sil(?:in(?:iz)?|sin(?:ler)?|sene|ebilir\\p{L}*|elim|iver|meni|mesini|mek|me)?${W1}|kald[ıi]r|[çc][ıi]kar|temizle|yok\\s*et)|unut(?:sun|sana|sunlar)?${W1}|" +
                "unut\\s*(?:gitsin|uver)|unutmas[ıi]n[ıi]\\s*istiyorum|unutman[ıi]\\s*istiyorum|unutmas[ıi]\\s*gerek",
        ),
        deleteNegation = Regex(
            "(?:silme|kald[ıi]rma|[çc][ıi]karma|temizleme|delete\\s*etme)(?:y[ıi]n(?:[ıi]z)?)?${W1}|unutma(?:y[ıi]n(?:[ıi]z)?)?${W1}|" +
                "(?:silmene|kald[ıi]rmaya|[çc][ıi]karmaya|temizlemeye)\\s*gerek\\s*yok|(?:silmek|kald[ıi]rmak)\\s*istemi\\w*|" +
                "koru|sakla(?:y[ıi]n(?:[ıi]z)?)?${W1}|" + TR_KEEP,
        ),
        hypothetical = Regex(
            "(?:senden|sizden)\\s.{0,40}?\\b(?:istese[mk]|istemi[şs]\\s+olsayd[ıi]m)|" +
                "(?:e[ğg]er|sayet|diyelim\\s+ki|varsayal[ıi]m|d[uü][şs][uü]n\\s+ki|farz\\s+et\\s+ki)\\s+(?:ben\\s+)?(?:senden|sana|sizden|size)\\s.{0,40}?\\b(?:istese[mk]|istemi[şs]\\s+olsam|s[oö]ylesem|desem)\\b|" +
                "${W0}(?:sil|kald[ıi]r|[çc][ıi]kar|de[ğg]i[şs]tir|g[uü]ncelle)\\s+(?:dersem|desem|dedi[ğg]imde)${W1}",
        ),
        askingHow = Regex(
            "ne\\s+yapard[ıi]n|ne\\s+yapars[ıi]n|nas[ıi]l\\s+davran[ıi]rd[ıi]n|nas[ıi]l\\s+davran[ıi]rs[ıi]n|ne\\s+olur(?:du)?${W1}|nas[ıi]l\\s+(?:bir\\s+yol\\s+)?izlerdin|" +
                "ne\\s+yapman\\s+gerekirdi|nas[ıi]l\\s+(?:bir\\s+)?(?:tepki|kar[şs][ıi]l[ıi]k|yan[ıi]t|cevap)\\s+(?:verirsin|g[oö]sterirsin|verirdin)|ne\\s+derdin|ne\\s+dersin",
        ),
    )

    // العربية:احفظ、تذكّر、احتفظ بهذا、خزّن、سجّل、لا تنسَ。「هل تتذكر؟」是问它记不记得。一般不标元音,同形词多(تذكرة 票、تذكير 提醒),逐个排掉
    private val ar = Phrases(
        languages = listOf("ar"),
        prepare = { AR_DIACRITICS.replace(it, "") },
        save = Regex(
            "${AR_W0}(?:احفظ|احتفظ(?!ت)|تذكر(?!ة|ات|ير|ت|نا)|خزن|سجل(?!ات))|" +
                "(?:اجعل|ضع)(?:\\s+\\p{L}+){0,3}\\s+في\\s+ذاكرت|لا\\s+تنس[ىية]?|ما?تنس[اى]ش",
        ),
        recallQuestion = Regex("تتذكر|هل\\s+نسيت|ما\\s+تذكرت|تذكر\\p{L}*\\s*(?:هل|؟)"),
        saveNegation = Regex(
            "لا\\s+(?:تحفظ|تحتفظ|تتذكر|تخزن|تسجل)|من\\s+غير\\s+(?:ما\\s+)?(?:تحفظ|تحتفظ|تتذكر|تخزن|تسجل)|" +
                "بدون\\s+(?:ما\\s+)?(?:تحفظ|تحتفظ|تحفظ|تتذكر|تسجل)|بدون\\s+(?:حفظ|تسجيل|تخزين)|" +
                "لا\\s+(?:داعي|ضرورة|حاجة)\\s+(?:ل[ـ]?\\s*)?(?:حفظ|تسجيل|تخزين|ان\\s+تحفظ|أن\\s+تحفظ)|" +
                "انس[ىا]\\s+(?:هذا|ذلك|هذه|ال|امر|الأمر|الموضوع|موضوع|ما\\s|كل|عن|رقمي|عنواني)|انس${W1}\\s+(?:هذا|ذلك|هذه|ال|امر|الأمر|الموضوع|موضوع|ما\\s|كل|عن)",
        ),
        update = Regex(
            "${AR_W0}(?:عدل|صحح|استبدل|اختصر|بسط|ادمج|تعديل|تحديث|تصحيح|استبدال|اختصار|تبسيط|دمج)|" +
                "قم\\s+ب(?:تحديث|تعديل|تصحيح|تغيير)|(?:أعد|اعد)\\s+كتابة",
        ),
        updateNegation = Regex(
            "لا\\s+(?:تعدل|تغير|تغي?ر|تصحح|تحدث|تستبدل|تختصر|تبسط|تدمج|تلمس)|" +
                "لا\\s+(?:داعي|ضرورة|حاجة)\\s+(?:ل[ـ]?\\s*)?(?:تعديل|تغيير|تحديث|تصحيح)|" +
                "من\\s+غير\\s+(?:ما\\s+)?(?:تعدل|تغير|تعديل|تحديث)|بدون\\s+(?:تعديل|تغيير|تحديث)|" + AR_KEEP,
        ),
        delete = Regex(
            "${AR_W0}(?:احذف|حذف|امسح|مسح|أزل|ازل|إزالة|ازالة|تخلص)|قم\\s+ب(?:حذف|مسح|إزالة|ازالة)|" +
                "انس[ىا]${W1}|انس${W1}\\s+(?:هذا|ذلك|هذه|ال|امر|الأمر|الموضوع|موضوع|ما\\s|كل|عن|رقمي|عنواني)",
        ),
        deleteNegation = Regex(
            "لا\\s+(?:تحذف|تمسح|تنس|تزل|تتخلص|تازل)|لا\\s+(?:داعي|ضرورة|حاجة)\\s+(?:ل[ـ]?\\s*)?(?:حذف|مسح|إزالة|ازالة|ان\\s+تحذف|أن\\s+تحذف)|" +
                "من\\s+غير\\s+(?:ما\\s+)?(?:تحذف|تمسح)|بدون\\s+(?:حذف|مسح|إزالة|ازالة)|" + AR_KEEP,
        ),
        hypothetical = Regex(
            "(?:لو|إذا|اذا|ماذا\\s+لو|في\\s+حال)\\s+(?:أنني|انني|أني|اني)?\\s*(?:قلت|طلبت|أمرتك|امرتك|خطر)|" +
                "(?:لنفترض|افترض|تخيل|فرضا)\\s+(?:أنني|انني|أني|اني)?\\s*(?:قلت|طلبت|أمرت|امرت)",
        ),
        askingHow = Regex(
            "ماذا\\s+(?:كنت\\s+)?(?:س|سوف)?(?:تفعل|ستفعل)|(?:كيف)\\s+(?:كنت\\s+)?(?:س|سوف)?(?:تتصرف|تتعامل|تفعل|تقوم|ترد|تعامل)|" +
                "ماذا\\s+(?:سي|كان\\s+سي|كان\\s+س|س)?حدث|كيف\\s+سيكون\\s+ردك|ماذا\\s+ستقول",
        ),
    )

    // فارسی:به خاطر بسپار、یادت باشه、یادت نره、فراموش نکن、ذخیره کن、یادداشت کن。「یادته؟」「به یاد داری؟」是问它记不记得
    private val fa = Phrases(
        languages = listOf("fa"),
        prepare = { it.replace('\u200C', ' ') },
        save = Regex(
            "به\\s*خاطر\\s*(?:بسپار|بنداز|نگه\\s*دار)|به\\s*یاد\\s*(?:داشته\\s*باش|نگه\\s*دار|بسپار)|یادت\\s*(?:باشه|باشد|بمونه|نره|مونه)|یادتون\\s*(?:باشه|باشد|بمونه|نره)|" +
                "به\\s*یادت\\s*(?:بسپار|نگه\\s*دار|داشته\\s*باش)|(?:حفظ|ذخیره|ثبت|یادداشت)(?:ش|شو|هاش|شون|شونو)?\\s*(?:رو\\s*)?(?:بکن|کن|کنید|کنین|کند|بکنید|بکنین)|" +
                "نگه\\s*دار|بسپار|(?:به|در|تو)\\s*(?:حافظه|ذهن)\\s*(?:ت|ات|تان|تون|مون)?\\s*(?:بسپار|ذخیره|نگه|ثبت|جا|بنداز)|" +
                "فراموش\\s*(?:نکن|نکنید|نکنین)|از\\s*یاد\\s*(?:نبر|نبرید|نبرین)|از\\s*یادت\\s*(?:نبر|نبری)|یادت\\s*نمونه",
        ),
        recallQuestion = Regex(
            "یادت\\s*(?:ه\\b|است|هست|ه\\s*[؟?])|یادتون\\s*(?:ه\\b|است|هست)|به\\s*یاد\\s*داری|یاد(?:ت|تون)?\\s*می[آا]د|به\\s*یاد(?:ت|تون)?\\s*(?:هست|اومده)|یادت\\s*(?:نیست|نمی[آا]د)",
        ),
        saveNegation = Regex(
            "به\\s*خاطر(?:ت|تان|تون)?\\s*نسپار|به\\s*یاد(?:ت|تون)?\\s*(?:نگه\\s*ندار|نداشته\\s*باش|نسپار)|یادت\\s*(?:نباشه|نباشد|نیفتد)|یادتون\\s*(?:نباشه|نباشد)|" +
                "(?:حفظ|ذخیره|ثبت|یادداشت|نگه\\s*داشتن|بسپار)(?:ش|شو|هاش|شون|شونو)?\\s*(?:رو\\s*)?(?:نکن|نکنید|نکنین|نکنه|نکنم)|نگه\\s*ندار|" +
                "لازم\\s*نیست\\s*(?:به\\s*خاطر\\s*)?(?:بسپاری|بسپارید|ذخیره\\s*کنی|حفظ\\s*کنی|یادداشت\\s*کنی)|نیازی\\s*به\\s*(?:حفظ|ذخیره|ثبت|یادداشت)\\s*(?:نیست|نداره)|" +
                "فراموش\\s*(?:ش|شو)?\\s*(?:کن|کنید|کنین|کنه)|یادت\\s*بره|از\\s*یاد(?:ت|تون)?\\s*ببر|از\\s*(?:حافظه|ذهن)\\s*(?:ت|تان|تون)?\\s*(?:پاک|حذف)\\s*(?:کن|بکن|بکنید)",
        ),
        update = Regex(
            "(?:تغییر|ویرایش|اصلاح|آپدیت|تصحیح|ادغام|جایگزین|بازنویسی|به\\s*روزرسانی|به\\s*روز\\s*رسانی|کوتاه\\s*(?:تر\\s*)?)(?:ش|شو|هاش|شون|شونو)?\\s*(?:رو\\s*)?(?:بده|بدید|بدهید|کن|کنید|کنین|کند|کنم|بکن)",
        ),
        updateNegation = Regex(
            "(?:ویرایش|تغییر|اصلاح|آپدیت|تصحیح|ادغام|جایگزین)(?:ش|شو|هاش|شون|شونو)?\\s*(?:رو\\s*)?(?:نکن|نکنید|نکنین|نکنه|نکنم)|" + FA_KEEP,
        ),
        delete = Regex(
            "(?:حذف|پاک)(?:ش|شو|هاش|شون|شونو)?\\s*(?:رو\\s*)?(?:کن|کنید|کنین|کند|کنم|بکن|بکنید|بکنین|بکنه|بکنم|کنه|بشه|شد)|" +
                "یادت\\s*بره|یادتون\\s*بره|از\\s*یاد\\s*ببر|از\\s*یادت\\s*ببر|فراموش\\s*(?:کن|کنید|کنین|کنه)|" +
                "از\\s*(?:حافظه|ذهن)\\s*(?:ت|تان|تون|مون)?\\s*(?:پاک|حذف|ببر|بردار)",
        ),
        deleteNegation = Regex(
            "(?:حذف|پاک|ویرایش|تغییر|اصلاح|آپدیت|تصحیح|ادغام|جایگزین)(?:ش|شو|هاش|شون|شونو)?\\s*(?:رو\\s*)?(?:نکن|نکنید|نکنین|نکنه|نکنم)|" +
                "یادت\\s*نره|یادتون\\s*نره|فراموش\\s*(?:نکن|نکنید|نکنین)|از\\s*یاد(?:ت|تون)?\\s*نبر|" + FA_KEEP,
        ),
        hypothetical = Regex(
            "(?:اگه|اگر|فرض\\s*کن|تخیل\\s*کن|فرض\\s*بگیر)(?:\\s+(?:من|بهت|به\\s+شما|به\\s+تو|ازت|از\\s+شما))?\\s+(?:بگم|بگویم|گفتم|گفتیم|بخوام|بخواهم|خواستم|بخواستم)",
        ),
        askingHow = Regex(
            "(?:چه\\s*کاری|چه\\s*کار|چیکار|چيکار|چه|چطور|چگونه)\\s*(?:می\\s*کنی|می\\s*کنید|می\\s*کردی|می\\s*کردید|می\\s*خوای|می\\s*خواید|خواهی\\s*کرد|خواهید\\s*کرد)|" +
                "چه\\s*پیش\\s*می[آا]د|چه\\s*اتفاقی\\s*(?:می\\s*افت\\s*اد|می\\s*افت\\s*ه|می\\s*افته|رخ\\s*می\\s*ده)|رفتار(?:ت|تان)?\\s*(?:چطور|چگونه)",
        ),
    )

    // Tiếng Việt:hãy nhớ、nhớ giúp、ghi nhớ、lưu lại、đừng quên。「còn nhớ không?」「bạn có nhớ…?」是问它记不记得。必须带声调符号才认(去掉后 nhớ/nhờ/nhỏ 同形)
    private val vi = Phrases(
        languages = listOf("vi"),
        prepare = { it.lowercase() },
        save = Regex(
            "hãy\\s+nhớ|nhớ\\s+(?:giúp|giùm|gium|hộ|hỗ|dùm|dum|lấy|đi|nhé|nha|nè|cho|kỹ|vô|vào|thôi|đấy)(?!\\p{L})|ghi\\s+nhớ|lưu\\s+(?:lại|vô|vào|giúp|giùm|hộ|dùm|cho)|" +
                "cất\\s+lưu|ghi\\s+lại|vui\\s+lòng\\s+(?:ghi\\s+nhớ|lưu|nhớ)|đừng\\s+(?:có\\s+)?quên|chớ\\s+(?:có\\s+)?quên|không\\s+được\\s+(?:có\\s+)?quên",
        ),
        recallQuestion = Regex(
            "nhớ\\s+(?:không|hả|chứ|chưa|hông|ko|k|hg|chớ|à|nhỉ|há|gì|sao|được|ra|lại|đâu)(?!\\p{L})|(?:còn|có|vẫn)\\s+nhớ(?!\\s+(?:giúp|giùm|hộ|dùm|lấy|đi|nhé|nha|nè|cho|kỹ|vô|vào))|" +
                "(?:bạn|anh|chị|em|cậu|mày|ông|bà|thầy|cô|con)\\s+(?:còn\\s+|có\\s+|vẫn\\s+)?nhớ(?!\\s+(?:giúp|giùm|hộ|dùm|lấy|đi|nhé|nha|nè|cho|kỹ|vô|vào))|(?:mình|tôi|tui)\\s+nhớ(?!\\s+(?:giúp|giùm|hộ|dùm|lấy|đi|nhé|nha|nè|cho|kỹ))",
        ),
        saveNegation = Regex(
            "đừng\\s+(?:có\\s+)?(?:nhớ|ghi\\s+nhớ|lưu(?:\\s+lại)?|ghi\\s+lại|save)|chớ\\s+(?:nhớ|ghi\\s+nhớ|lưu)|" +
                "không\\s+(?:cần|cần\\s+thiết|muốn\\s+(?:bạn|anh|chị|em)|phải)\\s+(?:\\p{L}+\\s+){0,3}?(?:nhớ|ghi\\s+nhớ|lưu|ghi\\s+lại)|không\\s+nhớ|" +
                "quên\\s+đi|quên\\s+(?:cái\\s+)?(?:đó|này|nó)\\s+đi|hãy\\s+quên|cứ\\s+quên|quên\\s+hết",
        ),
        update = Regex("sửa|chỉnh|cập\\s*nhật|thay\\s*đổi|viết\\s*lại|diễn\\s*đạt\\s*lại|rút\\s*gọn|đơn\\s*giản\\s*hóa|gộp|hợp\\s*nhất|thay\\s*thế|hiệu\\s*chỉnh"),
        updateNegation = Regex(
            "đừng\\s+(?:\\p{L}+\\s+){0,3}?(?:sửa|chỉnh|cập\\s*nhật|thay\\s*đổi|viết\\s*lại|gộp|thay\\s*thế|edit|update)|chớ\\s+(?:\\p{L}+\\s+){0,3}?(?:sửa|chỉnh|thay\\s*đổi)|" +
                "không\\s+(?:cần|được|muốn|phải)\\s+(?:\\p{L}+\\s+){0,3}?(?:sửa|chỉnh|cập\\s*nhật|thay\\s*đổi|viết\\s*lại)|" + VI_KEEP,
        ),
        delete = Regex("xóa|xoá|gỡ\\s*bỏ|loại\\s*bỏ|quên\\s+đi|quên\\s+(?:cái\\s+)?(?:đó|này|nó)\\s+đi|hãy\\s+quên"),
        deleteNegation = Regex(
            "đừng\\s+(?:\\p{L}+\\s+){0,3}?(?:xóa|xoá|gỡ|loại|quên|delete)|chớ\\s+(?:\\p{L}+\\s+){0,3}?(?:xóa|xoá|quên|gỡ)|" +
                "không\\s+(?:cần|được|muốn|phải)\\s+(?:\\p{L}+\\s+){0,3}?(?:xóa|xoá|gỡ|loại|quên)|" + VI_KEEP,
        ),
        hypothetical = Regex(
            "(?:nếu|giả\\s+sử|nếu\\s+như|ví\\s+dụ|thử\\s+nghĩ)\\s+(?:mình|tôi|em|anh|chị|con|tui|bạn)\\s+(?:bảo|nói|yêu\\s+cầu|nhờ|đề\\s+nghị|ra\\s+lệnh|dặn|kêu)",
        ),
        askingHow = Regex(
            "bạn\\s+(?:sẽ|định|dự\\s+định|có\\s+định|tính)\\s+(?:làm\\s+gì|làm\\s+thế\\s+nào|xử\\s+lý\\s+thế\\s+nào|xử\\s+lý\\s+ra\\s+sao|trả\\s+lời\\s+thế\\s+nào|hành\\s+động\\s+thế\\s+nào|đối\\s+phó\\s+thế\\s+nào|làm\\s+sao)|" +
                "sẽ\\s+(?:xảy\\s+ra|xảy\\s+ra\\s+chuyện\\s+gì|có\\s+chuyện\\s+gì|diễn\\s+ra|ra\\s+sao|thế\\s+nào)(?!\\p{L})|chuyện\\s+gì\\s+sẽ\\s+(?:xảy\\s+ra|diễn\\s+ra)|" +
                "điều\\s+gì\\s+sẽ\\s+(?:xảy\\s+ra|diễn\\s+ra)|bạn\\s+(?:xử\\s+lý|hành\\s+động|trả\\s+lời|làm)\\s+(?:thế\\s+nào|ra\\s+sao)",
        ),
    )

    // Bahasa Indonesia:ingat、catat、simpan、hafalkan、jangan lupa。「kamu ingat nggak?」「masih ingat?」是问它记不记得
    private val id = Phrases(
        languages = listOf("id"),
        prepare = { it.lowercase() },
        save = Regex(
            "(?:tolong|mohon)\\s+(?:ingat|diingat|disimpan|dicatat)|harap\\s+diingat|diingat\\s+ya|ingat\\s+(?:ini|itu|ya|selalu|terus|baik-baik|dengan\\s+baik|bahwa|soal|soalnya)(?!\\p{L})|" +
                "(?:catat|simpan|hafalkan)(?!\\p{L})|jangan\\s+(?:sampai\\s+)?lupa|jangan\\s+dilupakan|jangan\\s+lupakan",
        ),
        recallQuestion = Regex(
            "ingat\\s+(?:nggak|ngga|gak|ga|enggak|engga|tidak|tak|kan|kah)(?!\\p{L})|(?:masih|apa|apakah|begitu)\\s+ingat(?!\\p{L})|ingat\\s*[?？]|" +
                "(?:kamu|anda|kau|lo|lu|kalian)\\s+ingat(?!\\s+(?:ini|itu|ya|selalu|terus|bahwa|soal))|(?:aku|saya|gue)\\s+ingat|lupa\\s*(?:nggak|gak|ga|tidak|tak|enggak)",
        ),
        saveNegation = Regex(
            "jangan\\s+" + ID_GAP + "(?:catat|simpan|hafal|ingat|dicatat|disimpan|dihafal|diingat|di-?save|save)|" +
                "(?:tidak|nggak|gak|ga)\\s+(?:perlu|usah|jadi)\\s+(?:\\p{L}+\\s+){0,3}?(?:catat|simpan|hafal|ingat|dicatat|disimpan|diingat)|" +
                "tanpa\\s+(?:mencatat|menyimpan|menghafal)|lupakan\\s+(?:saja|aja)",
        ),
        update = Regex(
            "${W0}(?:perbarui|ubah|rubah|edit|koreksi|revisi|tulis\\s+ulang|ringkas|gabung|ganti|sesuaikan|sunting|rapikan|perbaiki)(?!\\p{L})",
        ),
        updateNegation = Regex(
            "jangan\\s+" + ID_GAP + "(?:ubah|rubah|edit|koreksi|revisi|ganti|sunting|rapikan|perbaiki|diubah|diedit|direvisi|diganti|disunting|di-?(?:edit|update|save)|update)|" +
                "(?:tidak|nggak|gak|ga)\\s+(?:perlu|usah|jadi)\\s+(?:\\p{L}+\\s+){0,3}?(?:ubah|edit|koreksi|revisi|ganti|sunting)|" + ID_KEEP,
        ),
        delete = Regex(
            "${W0}(?:hapus|apus|hilangkan|buang|lupakan|singkirkan|dihapus|dibuang|dilupakan)(?!\\p{L})|(?:tolong|mohon|bisa)\\s+dihapus",
        ),
        deleteNegation = Regex(
            "jangan\\s+" + ID_GAP + "(?:hapus|apus|hilangkan|buang|lupakan|singkirkan|dihapus|dibuang|dilupakan|di-?delete|delete|remove)|" +
                "(?:tidak|nggak|gak|ga)\\s+(?:perlu|usah|jadi)\\s+(?:\\p{L}+\\s+){0,3}?(?:hapus|dihapus|apus|buang|hilangkan|lupakan)|" + ID_KEEP,
        ),
        hypothetical = Regex(
            "(?:kalau|jika|kalo|seandainya|andai|seumpama|bagaimana\\s+(?:jika|kalau|kalo))\\s+(?:saya|aku)\\s+(?:minta|bilang|suruh|menyuruh|perintah(?:kan)?|nyuruh|pesan)",
        ),
        askingHow = Regex(
            "apa\\s+yang\\s+(?:akan\\s+|bakal\\s+|mau\\s+)?(?:kamu|anda|kau|lo|lu|kalian)\\s+(?:lakukan|perbuat|buat|kerjakan)|" +
                "bagaimana\\s+(?:kamu|anda|kau|lo|lu|kalian)\\s+(?:akan\\s+|bakal\\s+|mau\\s+)?(?:bereaksi|menanggapi|menanggap|bertindak|memproses|menangani|merespons|merespon|menjawab)|" +
                "(?:bagaimana|gimana)\\s+(?:kalau|jika|kalo|caranya|cara\\s|melakukannya|prosesnya|langkah)|apa\\s+yang\\s+terjadi",
        ),
    )

    /** 中英文以外的各块。新加一种语言:上面写一块,加进这里,再在 MemoryRequestsTest 里给它一组句子。 */
    private val others = listOf(ja, ko, ru, es, pt, tr, ar, fa, vi, id)

    /** 认得哪几种界面语言;MemoryRequestsTest 拿它对照 app 的 values-<语言> 目录,两边不能脱节。 */
    internal val languages: List<String> get() = (listOf(zhEn) + others).flatMap { it.languages }

    /**
     * 这半句话让它记了没有。中英文有说法时按原来的规矩定,只是别的语言里否定词不与中英文撞形的(「保存しないで」「jangan save」)
     * 说不让记也算不让;没有中英文说法时,其余语言任一种有说法就算,但任一种的不让记出现在这半句里就不算——
     * 西语、葡语共用 guarda、elimina 这些动词,否定词却不同。
     */
    fun asksToRemember(clause: String): Boolean {
        val c = clause.replace('’', '\'')
        zhEn.asksToRemember(c)?.let { own -> return own && others.none { !it.negationLooksEnglish && it.negatesRemember(c) } }
        return others.any { it.asksToRemember(c) == true } && others.none { it.negatesRemember(c) }
    }

    /** 本轮原话是否明确要求这次改或删。[quote] 是引文,[messages] 是含着它的整句;规矩同 [asksToRemember]。 */
    fun requested(op: String, quote: String, messages: List<String>): Boolean {
        if (zhEn.requests(op, quote)) {
            if (messages.any { zhEn.negates(op, it) || zhEn.onlyAsking(it) }) return false
            return messages.none { m ->
                val c = m.replace('’', '\'')
                others.any { it.onlyAsking(c) || (!it.negationLooksEnglish && it.negates(op, c)) }
            }
        }
        val q = quote.replace('’', '\'')
        return others.any { it.requests(op, q) } &&
            messages.none { m -> val c = m.replace('’', '\''); others.any { it.negates(op, c) || it.onlyAsking(c) } }
    }
}

private const val W0 = "(?<!\\p{L})"

private const val W1 = "(?!\\p{L})"

/** 名词后面直接接这些,是在问界面、方法或词义(「編集画面はどこ」「削除って何」),不是让它改删。 */
private const val JA_NOT_REQUEST = "(?!画面|方法|やり方|仕方|手順|ボタン|アイコン|メニュー|履歴|場所|機能|ページ|項目|一覧|は|が|って|とは|という)"

/** 「そのままにしておいて」「残して」:不让改、不让删的通用说法。 */
private const val JA_KEEP =
    "そのまま(?:にし|にして|にしてお|で(?:お|い|結構|大丈夫|いい)|保持|残し|に|で)|いじら(?:ないで|ず|ない)|触ら(?:ないで|ず|ない)|" +
        "残し(?:てお|ておい|てく|てくだ|て|といて)|置い(?:てお|てく|てくだ|ておい|て|といて)|忘れない|忘れずに"

/** 「기억해 줘」「기억해 주세요」「기억해 둬」是让它记;「기억해야 해」「기억해 봐」「기억해요?」不算。 */
private fun koRequest(stem: String) = "${stem}해(?!\\s*(?:야|봐|보|요))"

/** 名词后面直接接这些,是在问界面、位置或词义(「수정 화면은 어디」「삭제는 뭐야」),不是让它改删。 */
private const val KO_NOT_REQUEST =
    "(?!\\s*(?:화면|방법|버튼|아이콘|메뉴|기록|목록|기능|페이지|설정)|은|는|이|가|이라|란|이란|라는|이라는|이라고|하고|해서|하면|하니|하냐)"

private const val KO_KEEP =
    "(?:그대로|그냥|원래대로|그대)(?:로)?\\s*(?:두|놔|둬|놓|주세요|둬요|놔요|유지|가|만|살려|남겨|냅\\s*(?:두|둬|주세요))|" +
        "남겨\\s*(?:줘|주세요|둬|두|놔|놓|달|주|요|봐)|살려\\s*(?:줘|주세요|둬|두|놔|놓|달)|내버려|내버리|그대로\\s*유지|" +
        "건드리지\\s*(?:마|말)|손대지\\s*(?:마|말)|만지지\\s*(?:마|말)|잊지\\s*(?:마|말)|잊지\\s*않|잊어버리지\\s*(?:마|말)|버려\\s*(?:두|둬|놔|놓)"

private const val KO_UPDATE_WORDS = "수정|변경|편집|업데이트|갱신|고치|고쳐|바꾸|바꿔|정리|줄이|줄여|합치|합쳐|대체|교체"

private const val KO_DELETE_WORDS = "삭제|제거|지우|지워|없애|버리|버려|까먹"

private fun koNegated(verbs: String) =
    "(?:$verbs)(?:하)?\\s*지\\s*(?:마|말|않)|안\\s*(?:$verbs)|" +
        "(?:$verbs)(?:을|를|은|는|할)?\\s*(?:안|못)\\s*(?:해|하|할|하고|했다|할게|할래|해도|한다|된)|" +
        "(?:$verbs)(?:할|하는|하고)?\\s*필요\\s*(?:는|은)?\\s*없"

private const val RU_UPDATE_WORDS =
    "перепиш|переформул|сократ|упрост|объедин|замен|поменяй|поправ|корректир|переработ|подправ|перепис|отредактир|редактир"

private const val RU_DELETE_WORDS =
    "сотр(?:и|ите)|стирай(?:те)?|стереть|убер|убир|забудь(?:те)?|забыть|забывать|очист|вычеркн|выброс|выкин"

private const val RU_UPDATE_NEGATION_WORDS =
    "меняй|менять|измен|поменяй|помен|обнов|исправ|редактир|перепиш|сократ|упрост|объедин|замен|поправ|корректир|подправ|трогай"

private const val RU_DELETE_NEGATION_WORDS = "удал|сотр|стир|убер|убир|забудь|забыва|забыт|очист|вычеркн|выброс|выкин|трогай"

/** 「не」和动词之间常夹着宾语(「не надо ничего удалять」);只放行这几个代词,别的词(「не знаю, удали…」)不算否定。 */
private const val RU_GAP = "(?:(?:этот|эту|этого|этой|ту|то|тот|того|его|е[её]|их|ничего|вообще)\\s*){0,3}"

private const val RU_KEEP =
    "оставь(?:те)?\\s+(?:как\\s+есть|так|это|вс[её]|его|е[её]|их|на\\s+месте|в\\s+покое|в\\s+стороне|нетронутым|нетронутой)|" +
        "пусть\\s+(?:оста[её]тся|стоит|останется|будет|остаётся)|не\\s+трогай|не\\s+трогайте|не\\s+трогать|не\\s+прикасайся|не\\s+прикосайся"

/** 不让改 / 不让删:「не меняй」「не надо удалять」;「оставь как есть」「не трогай」两种都不让。 */
private fun ruNegated(verbs: String) =
    "не\\s+(?:надо|нужно|стоит|обязательно|требуется|пока)?\\s*" + RU_GAP + "(?:$verbs)|" +
        "не\\s+(?:хочу|желаю|намерен|собираюсь),?\\s*(?:чтобы\\s+(?:ты|вы)\\s+\\p{L}*?)?\\s*" + RU_GAP + "(?:$verbs)|" +
        "(?:$verbs)\\p{L}*\\s+не\\s+(?:надо|нужно|стоит|обязательно)|можно\\s+не\\s+(?:$verbs)"

/**
 * 「no / não」和动词之间常夹着宾语、quero、que 之类的词(「no quiero que lo guardes」「não quero que você anote」);
 * 只放行这几个,别的词(「no sé, apunta…」「não sei, anota…」)不算否定。西语、葡语共用动词多、否定词只差一个字母,两块用同一份。
 */
private const val ES_GAP =
    "(?:(?:quiero|queres|quero|precisa|preciso|necesito|necesario|hace|falta|vayas|favor|você|voce|usted|senhor|senhora|isso|isto|esto|este|esta|esse|essa|ese|esa|eso|" +
    "aquele|aquela|aquell|meu|minha|nada|todo|tudo|tus|teus|tuas|los|las|les|lhe|que|por|como|assim|asi|de|em|en|mi|mis|lo|la|le|me|te|nos|se|tu|es|é|va|vá|a|e)\\s*){0,6}"

/** 动词后面直接黏着的宾语代词(guárdalo、cámbiamelo);用在要求词尾完整的那些动词上,免得 guarded、editor 这类英文词被当成西语。 */
private const val ES_CL = "(?:me|te|se|nos|os|l[oa]s?|les?){0,2}"

/** 葡语动词后用连字符接的宾语代词(guarda-o、tire-me)。 */
private const val PT_CL = "(?:-(?:o|a|os|as|lo|la|los|las|me|te|se|nos|lhe|lhes))?"

private const val ES_UPDATE_WORDS = "actualiz|cambi|modifi|corri|correcci|edit|reescrib|reformul|acort|simplif|fusion|combin|reemplaz|sustitu|ajust|rectifiqu|rectific|toqu|tires"

private const val ES_DELETE_WORDS = "borr|elimin|suprim|remov|remuev|quit|olvid|sac|descart|deshac|toqu|tires"

private const val ES_KEEP =
    "conserv|mant[eé]n|mantiene|guarda(?:r)?\\s+(?:eso|esa|ese|aquell\\p{L}*|el|la|lo|las|los)|deja(?:r)?\\s+(?:eso|esa|ese|aquell\\p{L}*|el|la|lo|las|los|as[ií]|asi|como|tal|igual)"

private fun esNegated(verbs: String) = "(?:no|n[aã]o)\\s+" + ES_GAP + "(?:$verbs)|sin\\s+(?:\\p{L}+\\s+){0,2}(?:$verbs)|" + ES_KEEP

/** 不让改删的词。 */
private const val PT_UPDATE_WORDS = "mud|alter|modif|atualiz|corr|edit|reescrev|reformul|encurt|simplif|fund|junt|combin|substitu|troc|toc"

private const val PT_DELETE_WORDS = "apag|delet|elimin|esque|remov|tir|exclu|descart|limp|borr|toc"

private const val PT_KEEP =
    "conserv|mant(?:en|[eé]m)|preserv|guarda(?:r)?\\s+(?:isso|isto|esse|essa|aquele|aquela|o|a|os|as)|deix(?:a|e)\\s+(?:isso|isto|esse|essa|aquilo|o|a|os|as|como|assim)"

private fun ptNegated(verbs: String) = "(?:no|n[aã]o)\\s+" + ES_GAP + "(?:$verbs)|sem\\s+(?:\\p{L}+\\s+){0,2}(?:$verbs)|" + PT_KEEP

/** 「olduğu gibi bırak」「aynen kalsın」:不让改、不让删的通用说法。 */
private const val TR_KEEP =
    "(?:oldu[ğg]u\\s*gibi|oldu[ğg]u\\s*haliyle|aynen|ayn[ıi]\\s*(?:şekilde|haliyle)|[öo]yle|b[öo]yle)\\s*(?:b[ıi]rak|kals[ıi]n|dursun|kal|dur)|" +
        "dokunma|el\\s*atma|el\\s*s[uü]rme|de[ğg]i[şs]mesin|de[ğg]i[şs]memeli"

/** 阿拉伯文一般不标元音:发音符号和tatweel 先去掉再比。 */
private val AR_DIACRITICS = Regex("[\\u064B-\\u0652\\u0670\\u0640\\u0653-\\u0655]")

/** 词的起点。紧贴在前面的 و、ف 是连词(「وتذكر」= 并记住),不算前一个词的一部分。 */
private const val AR_W0 = "(?<![\\p{L}&&[^وف]])"

/** 「اتركه كما هو」(原样留着)、「لا تلمس」(别碰):不让改、不让删的通用说法。 */
private const val AR_KEEP =
    "اترك(?:ه|ها|هم|هن|كم)?${W1}|احتفظ\\s+ب|(?:زي|كما|مثل)\\s+ما\\s+هو${W1}|على\\s+ما\\s+هو|لا\\s+تلمس"

/** «همونجوری بذار»(原样放着)、「دست نزن」(别碰):不让改、不让删的通用说法。 */
private const val FA_KEEP =
    "نگهش\\s*دار|همونجوری\\s*(?:بذار|بمونه|نگهش\\s*دار)|همینجوری\\s*(?:بذار|بمونه)|همون\\s*طوری\\s*(?:بذار|بمونه)|همین\\s*طوری\\s*(?:بذار|بمونه)|" +
        "دست\\s*(?:رو\\s*)?نزن|دستت\\s*(?:رو\\s*)?نزن|بذار\\s*(?:همونجوری|همینجوری|همون\\s*طوری)\\s*(?:بمونه|بذار)"

/** 「giữ nguyên」(保持原样)、「đừng động vào」(别动):不让改、不让删的通用说法。 */
private const val VI_KEEP =
    "giữ\\s+(?:lại|nguyên|đi|nó|cho)|cứ\\s+để\\s+nguyên|để\\s+nguyên|đừng\\s+(?:động|đụng)\\s+vào|đừng\\s+làm\\s+gì|cứ\\s+giữ|vẫn\\s+giữ"

/** 「jangan」和动词之间只放行这几个宾语;「jangan lupa」(别忘了)里的 lupa 不在其中,是让它记。 */
private const val ID_GAP = "(?:(?:ini|itu|semua|semuanya|dia|mereka|nya|hal|yang|saja|aja|dulu)\\s+){0,3}"

/** 「biarkan saja」(就这样吧)、「pertahankan」(保留):不让改、不让删的通用说法。 */
private const val ID_KEEP =
    "biarkan\\s+saja|biar\\s+saja|tetap\\s+(?:disimpan|ada|seperti|sama|begitu|dipertahankan)|simpan\\s+saja|pertahankan|jangan\\s+di-?apa-?apa-?kan|jangan\\s+di-?ubah"
