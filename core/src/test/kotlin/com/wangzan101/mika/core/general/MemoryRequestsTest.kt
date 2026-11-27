package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.learn.memory.UserUtterances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 各语言里让它记、让它改删的说法。每句后面是中文对照,[Sample.want] 为真表示算用户这一轮明确要求了。
 * 让记的句子照 [MemoryTools] 里的用法,先按 [UserUtterances.quotedClauses] 分句再逐句判;改删的引文和整句都是这句。
 * 想认一种新说法就往对应语言的表里加一行;想不认某种语言,把 [MemoryRequests] 里那一块整块删掉,[languages] 会提醒改这里。
 * 中英文的说法由 MemoryToolsTest 里原有的用例守着,这里不重复。
 */
class MemoryRequestsTest {
    private class Sample(val text: String, val want: Boolean, val note: String)

    private fun assertRemember(vararg samples: Sample) {
        for (s in samples) {
            val said = UserUtterances().apply { add(s.text) }
            assertEquals(s.note + "「" + s.text + "」", s.want,
                said.quotedClauses(s.text).any { clauses -> clauses.any(MemoryRequests::asksToRemember) })
        }
    }

    private fun assertEdit(op: String, vararg samples: Sample) {
        for (s in samples) assertEquals("[$op] " + s.note + "「" + s.text + "」", s.want, MemoryRequests.requested(op, s.text, listOf(s.text)))
    }

    @Test fun languages() {
        assertEquals(listOf("zh", "en", "ja", "ko", "ru", "es", "pt", "tr", "ar", "fa", "vi", "id"), MemoryRequests.languages)
    }

    @Test fun japanese() {
        // ---- 日本語 ----
        assertRemember(
            // 让记
            Sample("この住所は覚えておいて", true, "记住这个地址"),
            Sample("私の誕生日を覚えといて", true, "记住我的生日"),
            Sample("その件は記憶しておいてください", true, "那件事请记下来"),
            Sample("この設定は保存して", true, "保存这个设置"),
            Sample("私のアレルギーは忘れないでね", true, "别忘了我的过敏"),
            Sample("住所は覚えないで、好きな色は覚えておいて", true, "地址别记,喜欢的颜色记下来"),
            // 不让记
            Sample("この情報は覚えないで", false, "这条信息别记"),
            Sample("この情報は記憶しないでください", false, "这条信息请不要记"),
            // 问问、陈述、只是提到「記憶」
            Sample("この前の住所は覚えてる？", false, "你记得以前那个地址吗"),
            Sample("私はそれを覚えていない", false, "我不记得那个"),
            Sample("昨日のことは覚えてた？", false, "昨天的事你还记得吗"),
            Sample("あなたの記憶機能はどう動きますか", false, "你的记忆功能是怎么工作的"),
            Sample("記憶について教えて", false, "讲讲记忆"),
            // 对抗样本：说话人自己要记、礼貌的问句、让对方记
            Sample("これ、絶対覚えておくね", false, "这个我绝对会记住(说话人自己要记)"),
            Sample("明日までに覚えておきます", false, "明天之前我会记住"),
            Sample("みんなで覚えておこう", false, "大家一起记住吧"),
            Sample("この件は覚えておこうと思います", false, "这件事我打算记住"),
            Sample("この住所は覚えておけよ", true, "这个地址你可要记住"),
            Sample("この件は覚えてくれる？", true, "这件事你能帮我记住吗"),
            Sample("昨日の件は覚えてたよね", false, "昨天的事你还记得吧"),
            Sample("あの住所は記憶にありますか", false, "那个地址在你记忆里吗"),
        )
        assertEdit(
            "update",
            // 让改 / 让删
            Sample("その住所の記憶を新しいものに変更して", true, "把地址那条记忆改成新的"),
            Sample("明日の予定の記憶を修正して", true, "把明天安排的记忆改一下"),
            Sample("その記憶をもう少し短くして", true, "把那条记忆再缩短一点"),
            // 不让改删
            Sample("その記憶は変更しないで", false, "那条记忆不要改"),
            // 没提改删
            Sample("もし私が引っ越したら、住所の記憶を変更して", true, "要是我搬家了,就把地址那条记忆改掉"),
            Sample("その記憶は変更しないといけない", true, "那条记忆必须改"),
            Sample("記憶の編集画面はどこ？", false, "记忆的编辑页面在哪"),
            Sample("その記憶には触らないで", false, "那条记忆别碰"),
            Sample("住所の記憶の編集をお願いします", true, "请帮我编辑地址那条记忆"),
        )
        assertEdit(
            "delete",
            // 让改 / 让删
            Sample("古い住所の記憶は削除して", true, "把旧地址那条记忆删掉"),
            Sample("その記憶を消して", true, "把那条记忆消掉"),
            Sample("私の誕生日は忘れて", true, "把我的生日忘掉"),
            // 不让改删
            Sample("その記憶は消さないで", false, "那条记忆别消"),
            Sample("住所の記憶は削除しないでね", false, "地址那条记忆别删"),
            Sample("そのままにしておいて", false, "保持原样"),
            // 只是问问
            Sample("もし私が「その記憶を削除して」と言ったら、あなたはどう対応しますか", false, "如果我说「把那条记忆删掉」,你会怎么处理"),
            // 没提改删
            Sample("その記憶はいつ登録したもの？", false, "那条记忆是什么时候存的"),
            Sample("その記憶を消していい？", true, "把那条记忆消掉可以吗"),
            Sample("もし「削除して」と言ったらどうなる？", false, "如果说「删掉」会怎么样"),
            Sample("古い住所は削除しなければならない", true, "旧地址必须删掉"),
            Sample("その記憶は残しておいて", false, "那条记忆留着"),
            Sample("削除ボタンはどこ？", false, "删除按钮在哪"),
            Sample("その記憶を削除", true, "删掉那条记忆"),
            Sample("削除ってどういう意味？", false, "「删除」是什么意思"),
        )
    }

    @Test fun korean() {
        // ---- 한국어 ----
        assertRemember(
            // 让记
            Sample("이 주소 기억해 줘", true, "记住这个地址"),
            Sample("제 생일을 기억해 두세요", true, "请记住我的生日"),
            Sample("이거 꼭 기억해놔", true, "这个一定要记牢"),
            Sample("저는 고양이 알레르기가 있으니까 잊지 마세요", true, "我对猫过敏,别忘了"),
            Sample("이 정보는 저장해 둬", true, "这个信息保存一下"),
            Sample("그건 기억하지 말고, 이것만 기억해", true, "那个别记,只记这个"),
            Sample("이거 기억하고 있어 줘", true, "这个帮我一直记着"),
            // 不让记
            Sample("이건 기억하지 마세요", false, "这个别记"),
            Sample("그 정보는 저장하지 마", false, "那个信息别保存"),
            Sample("이건 잊어버려", false, "忘掉这个"),
            Sample("기억에서 지워 버려", false, "从记忆里抹掉"),
            // 问问、陈述、只是提到「기억」
            Sample("제 주소 기억나?", false, "记得我家地址吗"),
            Sample("어제 뭐 먹었는지 기억해요?", false, "还记得昨天吃了什么吗"),
            Sample("제가 알레르기 있죠, 기억나요?", false, "我有过敏吧,还记得吗"),
            Sample("기억 안 나?", false, "想不起来吗"),
            Sample("기억력이 어떻게 작동해?", false, "记忆力是怎么运作的"),
            Sample("이건 기억할게요", false, "这个我会记住的"),
            Sample("이걸 기억해야 해", false, "这个必须记住(没说让谁记)"),
            Sample("그 얘기 기억하고 있어?", false, "那件事你还记得吗"),
        )
        assertEdit(
            "update",
            Sample("그 기억 좀 수정해 줘", true, "帮我改一下那条记忆"),
            Sample("주소 기록을 새 주소로 변경해 주세요", true, "请把地址那条记录改成新地址"),
            Sample("그 기억을 좀 더 짧게 줄여 줘", true, "把那条记忆缩短一点"),
            Sample("옛날 주소 기억을 고쳐 줘", true, "把旧地址那条记忆改一下"),
            Sample("그 기억은 바꾸지 마세요", false, "那条记忆请不要改"),
            Sample("수정 화면은 어디서 열어?", false, "编辑页面从哪里打开"),
            Sample("만약 이사 가면 주소 기억을 바꿔 줘", true, "要是搬家了,就把地址那条记忆改掉"),
            // 「안」空格不空格都算否定
            Sample("그건 안 바꿔도 돼", false, "那个不改也行"),
            Sample("그건안바꿔도 돼", false, "那个不改也行(没空格)"),
        )
        assertEdit(
            "delete",
            Sample("그 기억 삭제해 줘", true, "把那条记忆删掉"),
            Sample("옛날 주소 기억은 지워 버려", true, "把旧地址那条记忆抹掉"),
            Sample("그 기억을 없애 줘", true, "把那条记忆去掉"),
            Sample("그건 잊어버려", true, "那个忘掉"),
            Sample("그 기억 좀 삭제", true, "那条记忆,删掉"),
            Sample("그 기억을 삭제해도 돼?", true, "那条记忆可以删吗"),
            Sample("그 기억은 삭제하지 마", false, "那条记忆别删"),
            Sample("주소 기억은 지우지 마세요", false, "地址那条记忆请不要抹掉"),
            Sample("그대로 남겨 줘", false, "原样留着"),
            Sample("그냥 냅둬요", false, "就那么放着吧"),
            Sample("삭제 버튼이 어디 있어?", false, "删除按钮在哪"),
            Sample("만약 제가 그 기억을 지우라고 하면 어떻게 할 거야?", false, "要是我让你删掉那条记忆,你会怎么做"),
            // 「안」空格不空格都算否定
            Sample("그 기억은 안 지워도 돼", false, "那条记忆不删也行"),
            Sample("그건안지워도 돼", false, "那个不删也行(没空格)"),
        )
    }

    @Test fun russian() {
        // ---- Русский ----
        assertRemember(
            // 让记
            Sample("Запомни мой новый адрес: улица Ленина, 5", true, "记住我的新地址:列宁街 5 号"),
            Sample("Пожалуйста, запомните, что я вегетарианец", true, "请记住我是素食者"),
            Sample("Сохрани это в памяти", true, "把这个存在记忆里"),
            Sample("Возьми на заметку: я не пью кофе", true, "记一下:我不喝咖啡"),
            Sample("Не забудь, что у меня аллергия на арахис", true, "别忘了我对花生过敏"),
            Sample("Мой номер запиши себе", true, "我的新号码记下来"),
            Sample("Держи в уме, что по пятницам я ухожу рано", true, "记住,周五我走得早"),
            Sample("Помни, что я предпочитаю чай", true, "记住我更喜欢茶"),
            // 不让记
            Sample("Не запоминай этот номер", false, "别记这个号码"),
            Sample("Не нужно сохранять это", false, "不用保存这个"),
            Sample("Это не надо запоминать", false, "这个不用记"),
            Sample("Забудь про старый адрес", false, "忘掉旧地址"),
            Sample("Можно не запоминать", false, "可以不记"),
            // 问问、陈述、只是提到「память」
            Sample("Ты помнишь, какой у меня адрес?", false, "你记得我的地址吗"),
            Sample("Вы помните, что я говорил?", false, "您记得我说过的话吗"),
            Sample("Я помню, что ты говорил", false, "我记得你说过"),
            Sample("Я запомнил этот адрес", false, "我记住了这个地址"),
            Sample("Я запомню это", false, "我会记住这个"),
            Sample("Как работает твоя память?", false, "你的记忆是怎么工作的"),
            Sample("Запиши видео с экрана", false, "录一段屏幕视频(这里的 запиши 是「录制」)"),
            Sample("Не забудьте, что завтра встреча", true, "别忘了明天有会"),
            Sample("Забудь, что я говорил про чай", false, "忘掉我说的茶的事"),
            Sample("Запиши мой новый номер", true, "记下我的新号码"),
            Sample("Запиши разговор", false, "录一下通话"),
            Sample("Не хочу, чтобы ты запоминал мой адрес", false, "我不想让你记我的地址"),
            Sample("Не надо ничего запоминать", false, "什么都不用记"),
            Sample("Не забудь запомнить этот адрес", true, "别忘了记住这个地址"),
            // 「мне」词尾的 не 不是否定
            Sample("Запомни: мне нужно записать сына в школу", true, "记住:我得给儿子报名上学"),
            Sample("Этот адрес не сохраняй", false, "这个地址别保存"),
        )
        assertEdit(
            "update",
            Sample("Измени запись про адрес: теперь я живу в Казани", true, "改一下地址那条记录:我现在住在喀山"),
            Sample("Обнови мою запись о дне рождения", true, "更新我生日那条记录"),
            Sample("Сделай эту запись короче", true, "把这条记录弄短一点"),
            Sample("Ничего не меняй", false, "什么都别改"),
            Sample("Где журнал изменений?", false, "修改日志在哪(изменений 是名词「修改」)"),
            Sample("Проверь обновления приложения", false, "检查应用更新"),
            Sample("Если перееду, измени запись про адрес", true, "要是我搬家了,就改一下地址那条记录"),
            Sample("Не хочу ничего менять", false, "我什么都不想改"),
            // 「мне」词尾的 не 不是否定
            Sample("Помоги мне изменить запись об адресе", true, "帮我改一下地址那条记录"),
            Sample("Запись об адресе пока не меняй", false, "地址那条记录先别改"),
        )
        assertEdit(
            "delete",
            Sample("Удали запись про старый адрес", true, "把旧地址那条记录删掉"),
            Sample("Вычеркни старый адрес из памяти", true, "把旧地址从记忆里划掉"),
            Sample("Забудь мой прежний номер", true, "忘掉我以前的号码"),
            Sample("Не удаляй запись про адрес", false, "别删地址那条记录"),
            Sample("Не забудь мой адрес", false, "别忘了我的地址"),
            Sample("Оставь всё как есть", false, "一切保持原样"),
            Sample("Не трогай эту запись", false, "别动这条记录"),
            Sample("Где кнопка удаления?", false, "删除按钮在哪"),
            Sample("Если я попрошу удалить эту запись, что ты сделаешь?", false, "要是我请你删掉这条记录,你会怎么做"),
            Sample("Что бы ты сделал, если бы я попросил удалить это?", false, "要是我请你删掉这个,你会怎么做"),
            Sample("Ни в коем случае не удаляй", false, "无论如何都别删"),
            Sample("Не надо ничего удалять", false, "什么都不用删"),
            Sample("Удали, пожалуйста, запись про адрес", true, "请把地址那条记录删掉"),
            Sample("Не хочу, чтобы ты удалял эту запись", false, "我不想让你删这条记录"),
            Sample("Забудь старую запись", true, "把旧记录忘掉"),
            Sample("Я не уверен, но если что — удали запись про старый адрес", true, "我不确定,但万一的话就把旧地址那条记录删掉"),
            // 「мне」「телефоне」「машине」词尾的 не 不是否定
            Sample("Помоги мне удалить запись о старом адресе", true, "帮我把旧地址那条记录删掉"),
            Sample("Мне нужно удалить запись о старом адресе", true, "我要把旧地址那条记录删掉"),
            Sample("Запись о старом телефоне удали", true, "把旧手机那条记录删掉"),
            Sample("Запись о машине хочу удалить", true, "车那条记录我想删掉"),
            Sample("Запись про адрес не удаляй", false, "地址那条记录别删"),
            Sample("Мне не нужно удалять эту запись", false, "我不需要删这条记录"),
            Sample("Эту запись я не хочу удалять", false, "这条记录我不想删"),
        )
    }

    @Test fun spanish() {
        // ---- Español ----
        assertRemember(
            Sample("Recuerda que me mudé a Lima", true, "记住我搬到利马了"),
            Sample("Acuerdate de que no como cilantro", true, "记住我不吃香菜(没打重音)"),
            Sample("Guárdame este dato: trabajo de remoto los viernes", true, "帮我记下这条:周五远程办公"),
            Sample("Apunta mi número nuevo", true, "记下我的新号码"),
            Sample("Memoriza que soy alérgico al cacahuete", true, "记住我对花生过敏"),
            Sample("No olvides que soy vegetariano", true, "别忘了我是素食者"),
            Sample("No se te olvide, llego tarde los martes", true, "别忘了,周二我会迟到"),
            // 不让记
            Sample("No guardes nada de esta página", false, "这个页面的内容都别存"),
            Sample("No quiero que apuntes mi teléfono", false, "我不想让你记我的电话"),
            Sample("No es necesario que lo memorices", false, "不用背下来"),
            Sample("Olvida esa dirección", false, "忘掉那个地址"),
            // 问问、陈述、只是提到「memoria」
            Sample("¿Te acuerdas de la dirección que te di?", false, "你记得我给你的地址吗"),
            Sample("¿Recuerdas qué te pedí ayer?", false, "你记得昨天我请你做什么吗"),
            Sample("¿Cómo funciona tu memoria?", false, "你的记忆是怎么运作的"),
            Sample("El disco duro no tiene memoria suficiente", false, "硬盘内存不够"),
            Sample("Play this record and tell me the singer", false, "放这首唱片,告诉我歌手是谁(英文 record)"),
            Sample("The guard saved the day", false, "保镖救了那一天(英文 guard)"),
            Sample("No sé, apunta esto", true, "我不确定,先记下这个"),
            // 「vecino」「teléfono」词尾的 no 不是否定
            Sample("Recuerda que mi vecino guarda mis llaves", true, "记住邻居替我保管钥匙"),
            Sample("Eso no lo guardes", false, "那个别存"),
            // 「Yeltsin」「Wisconsin」词尾的 sin 不算否定
            Sample("Recuerda que en Wisconsin guardar el coche en la calle está prohibido", true, "记住:在威斯康星不能把车停在街上过夜"),
            Sample("Lee esto sin guardar nada", false, "读一下这个,什么都别存"),
        )
        assertEdit(
            "update",
            Sample("Cambia mi dirección en el recuerdo", true, "把记忆里的地址改一下"),
            Sample("Actualiza el recuerdo de mi teléfono", true, "更新我电话那条记忆"),
            Sample("No cambies nada todavía", false, "先什么都别改"),
            Sample("Abre los ajustes de la app", false, "打开应用设置"),
            Sample("Si me mudo, cambia mi dirección en el recuerdo", true, "要是我搬家了,就把记忆里的地址改掉"),
            // 「vecino」「teléfono」词尾的 no 不是否定
            Sample("Actualiza la nota: mi teléfono cambió", true, "更新那条笔记:我的电话换了"),
            Sample("Esa nota no la cambies", false, "那条笔记别改"),
            // 「Yeltsin」「Wisconsin」词尾的 sin 不算否定
            Sample("Actualiza la nota: Yeltsin cambió de número", true, "更新那条笔记:叶利钦换号码了"),
            Sample("Revisa la nota sin cambiar nada", false, "看一下那条笔记,什么都别改"),
        )
        assertEdit(
            "delete",
            Sample("Borra el recuerdo de mi dirección antigua", true, "把我旧地址那条记忆删掉"),
            Sample("Elimina ese dato, por favor", true, "请把那条数据删掉"),
            Sample("Olvida mi dirección antigua", true, "忘掉我的旧地址"),
            Sample("No borres el recuerdo de mi dirección", false, "别删我地址那条记忆"),
            Sample("No quiero que elimines ese dato", false, "我不想让你删那条数据"),
            Sample("No olvides mi dirección del trabajo", false, "别忘了我单位的地址"),
            Sample("Deja eso como está", false, "那个就这样"),
            Sample("Si te pidiera borrar ese recuerdo, ¿qué harías?", false, "要是我请你删掉那条记忆,你会怎么做"),
            // 「vecino」「teléfono」词尾的 no 不是否定
            Sample("Borra el recuerdo del teléfono olvidado en el taxi", true, "把手机落在出租车上那条记忆删掉"),
            Sample("Esa nota no la borres", false, "那条笔记别删"),
            // 「Yeltsin」「Wisconsin」词尾的 sin 不算否定
            Sample("Revisa la nota sin borrar nada", false, "看一下那条笔记,什么都别删"),
        )
    }

    @Test fun portuguese() {
        // ---- Português ----
        assertRemember(
            Sample("Lembra que não como glúten", true, "记住我不吃麸质"),
            Sample("Lembre-se de que minha filha se chama Ana", true, "记住我女儿叫安娜"),
            Sample("Guarde esta informação para mim", true, "帮我保存这条信息"),
            Sample("Salva o meu endereço novo", true, "存一下我的新地址"),
            Sample("Anota que chego às dez", true, "记下我十点到"),
            Sample("Não esqueça que tenho reunião amanhã", true, "别忘了明天我有会"),
            Sample("Não se esqueça do meu aniversário", true, "别忘了我的生日"),
            // 不让记
            Sample("Não guarde nada disso", false, "这些都别存"),
            Sample("Não quero que você anote meu telefone", false, "我不想让你记我的电话"),
            Sample("Esquece isso", false, "忘掉这个"),
            // 问问、陈述、只是提到「memória」
            Sample("Você lembra do meu endereço?", false, "你记得我的地址吗"),
            Sample("Lembra disso?", false, "记得这事吗"),
            Sample("Deixa um lembrete para mim", false, "给我留个提醒(lembrete 是「提醒」,不是「记住」)"),
            Sample("Como funciona sua memória?", false, "你的记忆是怎么运作的"),
            Sample("Eu lembro que você mora em SP", false, "我记得你住在圣保罗"),
            Sample("Não sei, anota isso", true, "我不知道,把这个记下来"),
            // 「Bruno」「plano」词尾的 no 不是否定
            Sample("Lembra que o Bruno guarda as chaves do escritório", true, "记住布鲁诺保管办公室的钥匙"),
            Sample("Isso não precisa guardar", false, "这个不用存"),
            // 「precisem」「avisem」词尾的 sem 不算否定
            Sample("Anota: talvez eles precisem guardar as caixas na garagem", true, "记下:他们可能得把箱子放进车库"),
            Sample("Lê isto sem guardar", false, "读一下这个,不用存"),
        )
        assertEdit(
            "update",
            Sample("Muda o endereço na lembrança", true, "把记忆里的地址改一下"),
            Sample("Atualiza a lembrança do meu telefone", true, "更新我电话那条记忆"),
            Sample("Não muda nada ainda", false, "先什么都别改"),
            // 「Bruno」「plano」词尾的 no 不是否定
            Sample("Atualiza a nota: o plano mudou", true, "更新那条笔记:计划变了"),
            Sample("Essa nota não precisa mudar", false, "那条笔记不用改"),
            // 「precisem」「avisem」词尾的 sem 不算否定
            Sample("Atualiza a nota: talvez precisem mudar a data", true, "更新那条笔记:他们可能得改日期"),
            Sample("Atualiza a nota quando avisem a mudança de horário", true, "等他们通知改时间了就更新那条笔记"),
            Sample("Lê a nota sem mudar nada", false, "读一下那条笔记,什么都别改"),
        )
        assertEdit(
            "delete",
            Sample("Apaga a lembrança do meu endereço antigo", true, "把我旧地址那条记忆删掉"),
            Sample("Tira esse dado daí", true, "把那条数据拿掉"),
            Sample("Esquece o meu endereço antigo", true, "忘掉我的旧地址"),
            Sample("Não apague essa lembrança", false, "别删那条记忆"),
            Sample("Não esqueça meu endereço do trabalho", false, "别忘了我单位的地址"),
            Sample("Deixa isso como está", false, "那个就这样"),
            Sample("Se eu te pedisse para apagar isso, o que você faria?", false, "要是我请你删掉这个,你会怎么做"),
            Sample("Como você reagiria se eu mandasse apagar isso?", false, "要是我让你删掉这个,你会怎么反应"),
            // 「Bruno」「plano」词尾的 no 不是否定
            Sample("Apaga a nota do plano de limpeza", true, "把清洁计划那条笔记删掉"),
            Sample("Essa nota não precisa apagar", false, "那条笔记不用删"),
            // 「precisem」「avisem」词尾的 sem 不算否定
            Sample("Lê a nota sem apagar nada", false, "读一下那条笔记,什么都别删"),
        )
    }

    @Test fun turkish() {
        // ---- Türkçe ----
        assertRemember(
            Sample("Bu adresi aklında tut", true, "记住这个地址"),
            Sample("Benim doğum günümü hatırla", true, "记住我的生日"),
            Sample("Bunu hafızana al", true, "把这个存进你的记忆"),
            Sample("Not al: cuma günleri uzaktan çalışıyorum", true, "记一下:周五我远程办公"),
            Sample("Fıstığa alerjim var, unutma", true, "我对花生过敏,别忘了"),
            Sample("Bunu kaydet", true, "存下这个"),
            Sample("Hatırlamanı istiyorum: annemin adı Ayşe", true, "我要你记住:我妈妈叫 Ayşe"),
            Sample("Bunları not et", true, "把这些记下来"),
            Sample("Bu bilgiyi aklında bulundur", true, "把这条信息记在心里"),
            Sample("Hatirla, Istanbul'da oturuyorum", true, "记住,我住在伊斯坦布尔(ı 打成了 i)"),
            // 不让记
            Sample("Bu bilgiyi hatırlama", false, "这条信息别记"),
            Sample("Bunu kaydetme", false, "这个别存"),
            Sample("Not alma", false, "别做笔记"),
            Sample("Bunu unutabilirsin", false, "这个你可以忘掉"),
            Sample("Aklına gelen her şeyi kaydetme", false, "想到什么都别存"),
            // 问问、陈述、只是提到「hafıza」
            Sample("Dün söylediklerimi hatırlıyor musun?", false, "还记得我昨天说的吗"),
            Sample("Benim adresimi hatırlar mısın?", false, "你记得我的地址吗"),
            Sample("Hatırladın mı?", false, "你想起来了吗"),
            Sample("Hafızan nasıl çalışıyor?", false, "你的记忆是怎么工作的"),
            Sample("Kaydetmeni istiyorum: yeni adresim Kadıköy", true, "我要你存下来:我的新地址在 Kadıköy"),
            Sample("Not almanı istiyorum", true, "我要你记个笔记"),
            Sample("Bunu hatırlamana gerek yok", false, "这个不用记"),
        )
        assertEdit(
            "update",
            Sample("Adres bilgimi güncelle", true, "更新我的地址信息"),
            Sample("O kaydı düzelt", true, "把那条记录改正好"),
            Sample("Kaydı değiştir: yeni numaram 555", true, "改一下记录:我的新号码是 555"),
            Sample("O kaydı biraz kısalt", true, "把那条记录缩短一点"),
            Sample("Kaydı değiştirme", false, "那条记录不要改"),
            Sample("Güncelleme ekranını aç", false, "打开更新界面"),
            Sample("Aynen kalsın", false, "保持原样"),
            Sample("Taşınırsam adres kaydını güncelle", true, "要是搬家了,就更新地址记录"),
            Sample("Update etme, sonra bakarız", false, "先别更新"),
        )
        assertEdit(
            "delete",
            Sample("Eski adres kaydını sil", true, "把旧地址记录删掉"),
            Sample("O kaydı kaldır", true, "把那条记录移除"),
            Sample("Numaramı hafızandan çıkar", true, "把我的号码从你的记忆里拿出去"),
            Sample("Şunu unut", true, "把那个忘掉"),
            Sample("Eski kaydı yok et", true, "把旧记录抹掉"),
            Sample("O kaydı silme", false, "那条记录别删"),
            Sample("Adres kaydımı kaldırma", false, "我的地址记录别移除"),
            Sample("Olduğu gibi bırak", false, "保持原样"),
            Sample("Fıstık alerjimi unutma", false, "别忘了我的花生过敏"),
            Sample("Silme butonu nerede?", false, "删除按钮在哪"),
            Sample("Senden o kaydı silmeni istesem ne yapardın?", false, "要是我请你删掉那条记录,你会怎么做"),
            Sample("Sil dersem ne yaparsın?", false, "要是我说「删掉」,你会怎么做"),
            Sample("Delete etme o kaydı", false, "那条记录别删"),
        )
    }

    @Test fun arabic() {
        // ---- العربية ----
        assertRemember(
            Sample("احفظ هذا العنوان", true, "记住这个地址"),
            Sample("تذكّر أني نباتي", true, "记住我是素食者"),
            Sample("احتفظ بهذا الرقم", true, "把这个号码留存好"),
            Sample("خزّن هذه المعلومات", true, "把这些信息存起来"),
            Sample("لا تنسَ أن لدي حساسية من الفول السوداني", true, "别忘了我对花生过敏"),
            Sample("وتذكر أن موعد الاجتماع يوم الثلاثاء", true, "并记住会议在周二"),
            Sample("اجعل هذا في ذاكرتك: عمري ثلاثون عاماً", true, "把这个记在你的记忆里:我三十岁"),
            // 不让记
            Sample("لا تحفظ هذه المعلومة", false, "这条信息别存"),
            Sample("انسَ ما قلته عن رقمي القديم", false, "忘掉我说过的旧号码"),
            Sample("بدون حفظ هذه المعلومات", false, "不保存这些信息"),
            // 问问、陈述、同形词(تذكرة 票)
            Sample("هل تتذكر عنواني؟", false, "你记得我的地址吗"),
            Sample("كم تذكرة الطائرة؟", false, "机票多少钱(تذكرة 是「票」,同形)"),
            Sample("ما هي ذاكرتك؟", false, "你的记忆是什么"),
            Sample("تذكرت أن عندي موعد", false, "我想起我有个约会"),
            // 分句:阿拉伯文逗号、问号后面才是让它记的那半句
            Sample("لا تحفظ رقمي، واحفظ عنواني", true, "我的号码别存,把我的地址记住(逗号后面才是让记的那半句)"),
            Sample("هل تتذكر عنواني؟ احفظ رقمي الجديد", true, "你记得我的地址吗?记住我的新号码"),
        )
        assertEdit(
            "update",
            Sample("عدل عنواني في الذاكرة", true, "把记忆里的地址改一下"),
            Sample("قم بتحديث الرقم المحفوظ", true, "更新已保存的号码"),
            Sample("أريد تعديل هذه المعلومة", true, "我想修改这条信息"),
            Sample("صحح اسم الشارع في الذاكرة", true, "把记忆里的街道名改对"),
            Sample("لا تعدل هذه المعلومة", false, "这条信息不要改"),
            Sample("إذا انتقلت، عدل عنواني في الذاكرة", true, "要是我搬家了,就把记忆里的地址改一下"),
        )
        assertEdit(
            "delete",
            Sample("احذف عنواني القديم من الذاكرة", true, "把我的旧地址从记忆里删掉"),
            Sample("امسح هذه المعلومة", true, "把这条信息抹掉"),
            Sample("انس عنواني القديم", true, "忘掉我的旧地址"),
            Sample("أزل هذا الرقم من ذاكرتك", true, "把这个号码从你的记忆里去掉"),
            Sample("لا تحذف عنواني", false, "别删我的地址"),
            Sample("لا تنسَ عنوان بيتي", false, "别忘了我家的地址"),
            Sample("اتركه كما هو", false, "就让它保持原样"),
            Sample("احتفظ بهذه المعلومة", false, "把这条信息留存好"),
            Sample("أين زر الحذف؟", false, "删除按钮在哪"),
            Sample("لو طلبت منك حذف هذا العنوان، ماذا ستفعل؟", false, "要是我请你删掉这个地址,你会怎么做"),
            Sample("ماذا لو قلت لك احذف هذه المعلومة، كيف ستتصرف؟", false, "要是我跟你说删掉这条信息,你会怎么应对"),
        )
    }

    @Test fun persian() {
        // ---- فارسی ----
        assertRemember(
            Sample("این آدرس رو به خاطر بسپار", true, "把这个地址记牢"),
            Sample("یادت باشه ساعت ۹ صبح جلسه دارم", true, "记住我早上 9 点有会"),
            Sample("من گیاه‌خوارم، فراموش نکن", true, "我是素食者,别忘了"),
            Sample("این شماره رو ذخیره کن", true, "把这个号码存下来"),
            Sample("یادت نره که پنجشنبه‌ها زود می‌رم", true, "别忘了周四我很早走"),
            Sample("این رو یادداشت کن", true, "把这个记下来"),
            Sample("در حافظه‌ت نگهش دار", true, "把它放在你的记忆里保管好"),
            // 不让记
            Sample("این اطلاعات رو ذخیره نکن", false, "这些信息别存"),
            Sample("یادداشت نکن", false, "别做记录"),
            Sample("فراموش کن چی گفتم", false, "忘掉我说的话"),
            Sample("یادت نباشه", false, "你不用记"),
            // 问问、陈述
            Sample("یادته بهت گفتم کجا کار می‌کنم؟", false, "记得我跟你说过我在哪工作吗"),
            Sample("به یاد داری چی گفتم؟", false, "你记得我说了什么吗"),
            Sample("یادت میاد؟", false, "你想起来了吗"),
            Sample("حافظه گوشی پر شده", false, "手机存储满了"),
        )
        assertEdit(
            "update",
            Sample("این یادداشت رو ویرایش کن", true, "把这条笔记编辑一下"),
            Sample("آدرس رو تغییر بده", true, "把地址改一下"),
            Sample("این مورد رو اصلاح کن", true, "把这一项修改一下"),
            Sample("متنش رو کوتاه‌تر کن", true, "把它的内容缩短一点"),
            Sample("ویرایشش نکن", false, "别编辑它"),
            Sample("دست نزن", false, "别碰"),
            Sample("دکمه ویرایش کجاست؟", false, "编辑按钮在哪"),
            Sample("اگه اسباب‌کشی کردم، آدرس رو تغییر بده", true, "要是我搬家了,就把地址改一下"),
        )
        assertEdit(
            "delete",
            Sample("این آدرس رو حذف کن", true, "把这个地址删掉"),
            Sample("پاکش کن", true, "把它删掉"),
            Sample("آدرس قدیمیمو یادت بره", true, "把我的旧地址忘掉"),
            Sample("این رو فراموش کن", true, "把这个忘掉"),
            Sample("این رو حذف نکن", false, "这个别删"),
            Sample("آدرس خونه‌م رو یادت نره", false, "我家的地址你别忘了"),
            Sample("همونجوری بذار", false, "就那么放着"),
            Sample("نگهش دار", false, "把它留着"),
            Sample("اگه ازت بخوام این رو حذف کنی، چیکار می‌کنی؟", false, "要是我请你把这个删掉,你会怎么做"),
            Sample("فرض کن بهت گفتم پاکش کن، چه کار می‌کنی؟", false, "假设我跟你说了「把它删掉」,你会怎么做"),
        )
    }

    @Test fun vietnamese() {
        // ---- Tiếng Việt ----
        assertRemember(
            Sample("Hãy nhớ địa chỉ mới của mình nhé", true, "记住我的新地址"),
            Sample("Nhớ giúp mình là mình dị ứng tôm", true, "帮我记着:我对虾过敏"),
            Sample("Ghi nhớ là thứ Sáu mình làm ở nhà", true, "记住周五我在家办公"),
            Sample("Lưu lại số điện thoại này", true, "把这个电话号码存下来"),
            Sample("Đừng quên mình họp lúc 9 giờ", true, "别忘了我 9 点有会"),
            Sample("Địa chỉ mới nhớ vào nhé", true, "新地址记一下"),
            Sample("Đừng nhớ số này", false, "别记这个号码"),
            Sample("Không cần lưu thông tin đó", false, "不用存那条信息"),
            Sample("Quên đi, để sau nhé", false, "先忘掉,以后再说"),
            Sample("Bạn còn nhớ địa chỉ cũ không?", false, "你还记得旧地址吗"),
            Sample("Mình nhớ là bạn đã nói rồi", false, "我记得你已经说过了"),
            Sample("Bạn có nhớ tôi không?", false, "你记得我吗"),
            Sample("Bộ nhớ điện thoại đầy rồi", false, "手机存储满了"),
        )
        assertEdit(
            "update",
            Sample("Sửa địa chỉ trong ghi chú giúp tôi", true, "帮我改一下笔记里的地址"),
            Sample("Cập nhật số điện thoại đã lưu", true, "更新已保存的电话号码"),
            Sample("Chỉnh lại địa chỉ nhà", true, "把家庭地址调整一下"),
            Sample("Viết lại cho ngắn gọn", true, "重写得更简洁"),
            Sample("Đừng sửa gì cả", false, "什么都别改"),
            Sample("Để nguyên", false, "保持原样"),
            Sample("Nếu mình chuyển nhà, sửa địa chỉ lại", true, "要是我搬家了,就把地址改一下"),
            Sample("Đừng edit thông tin đó", false, "那条信息别编辑"),
        )
        assertEdit(
            "delete",
            Sample("Xóa địa chỉ cũ đi", true, "把旧地址删掉"),
            Sample("Gỡ bỏ mục này", true, "把这一项去掉"),
            Sample("Quên cái đó đi", true, "把那个忘掉"),
            Sample("Loại bỏ số điện thoại cũ", true, "把旧电话号码去掉"),
            Sample("Đừng xóa địa chỉ nhà", false, "别删家庭地址"),
            Sample("Đừng quên địa chỉ nhà", false, "别忘了家庭地址"),
            Sample("Giữ nguyên", false, "保持原样"),
            Sample("Không cần xóa", false, "不用删"),
            Sample("Nếu mình bảo xóa mục này, bạn sẽ làm gì?", false, "要是我说删掉这一项,你会怎么做"),
            Sample("Giả sử em nói xóa, bạn sẽ xử lý thế nào?", false, "假设我说删掉,你会怎么处理"),
            Sample("Đừng delete mục này", false, "这一项别删除"),
        )
    }

    @Test fun indonesian() {
        // ---- Bahasa Indonesia ----
        assertRemember(
            Sample("Ingat ya, saya tidak makan pedas", true, "记住啊,我不吃辣"),
            Sample("Tolong ingat alamat baru saya", true, "请记住我的新地址"),
            Sample("Catat ini: hari Jumat saya kerja dari rumah", true, "记下来:周五我在家办公"),
            Sample("Simpan nomor ini", true, "存下这个号码"),
            Sample("Jangan lupa saya alergi kacang", true, "别忘了我对花生过敏"),
            Sample("Hafalkan bahwa saya suka teh hijau", true, "背住:我喜欢绿茶"),
            Sample("Tolong diingat, besok saya pulang cepat", true, "请记住,明天我早回家"),
            Sample("Jangan dicatat", false, "别记"),
            Sample("Jangan simpan nomor itu", false, "别存那个号码"),
            Sample("Tidak perlu diingat", false, "不用记"),
            Sample("Lupakan saja yang tadi", false, "刚才的就忘掉吧"),
            Sample("Kamu ingat nggak alamat saya?", false, "你记得我的地址吗"),
            Sample("Masih ingat apa yang saya bilang?", false, "还记得我说的话吗"),
            Sample("Aku ingat kamu pernah bilang begitu", false, "我记得你说过"),
            Sample("Memori ponsel saya penuh", false, "我手机内存满了"),
            Sample("Buat catatan rapat besok", false, "给明天的会做笔记(是在 App 里做笔记,不是记忆)"),
            // 「juga」(也)词尾的 ga 不算否定;enggak、ngga、ngak、kagak 照旧算
            Sample("Catat bahwa obat ini juga perlu disimpan di kulkas", true, "记下:这个药也要放冰箱"),
            Sample("Yang ini enggak perlu disimpan", false, "这个不用存"),
            // 「tunjangan」(津贴)词尾的 jangan 不算否定,「tunjangan lupa」也不算「jangan lupa」(别忘了)
            Sample("Catat bahwa tunjangan disimpan di rekening tabungan", true, "记下:津贴存在储蓄账户里"),
            Sample("Bulan ini tunjangan lupa ditransfer", false, "这个月的津贴忘了转"),
            Sample("Kemarin tunjangan dilupakan lagi", false, "昨天津贴又被忘了"),
            Sample("Soal tunjangan lupakan dulu", false, "津贴的事先放一放"),
            Sample("Nomor ini jangan disimpan", false, "这个号码别存"),
            Sample("Tolong jangan lupa saya alergi udang", true, "请别忘了我对虾过敏"),
        )
        assertEdit(
            "update",
            Sample("Perbarui alamat saya di catatan itu", true, "更新那条笔记里我的地址"),
            Sample("Ubah nomor telepon yang tersimpan", true, "改一下已保存的电话号码"),
            Sample("Edit catatan tentang alamat", true, "编辑关于地址的笔记(夹用英文 edit)"),
            Sample("Tulis ulang biar lebih ringkas", true, "重写一下,让它更简洁"),
            Sample("Jangan ubah apa pun", false, "什么都别改"),
            Sample("Jangan diedit dulu", false, "先别编辑"),
            Sample("Kalau saya pindah rumah, ubah alamat saya di catatan", true, "要是我搬家了,就改一下笔记里我的地址"),
            Sample("Jangan edit catatan itu", false, "那条笔记别编辑"),
            // 「juga」(也)词尾的 ga 不算否定;enggak、ngga、ngak、kagak 照旧算
            Sample("Catatan alamat itu juga perlu revisi", true, "地址那条笔记也要改"),
            Sample("Kamu ngga perlu ganti catatan itu", false, "你不用改那条笔记"),
            // 「tunjangan」(津贴)词尾的 jangan 不算否定
            Sample("Catatan tunjangan itu ganti jadi lima juta", true, "津贴那条笔记改成五百万"),
            Sample("Perbarui catatan: tunjangan diubah jadi lima juta", true, "更新笔记:津贴改成五百万了"),
            Sample("Alamatnya jangan ganti dulu", false, "地址先别改"),
        )
        assertEdit(
            "delete",
            Sample("Hapus catatan alamat lama", true, "把旧地址的笔记删掉"),
            Sample("Buang catatan itu", true, "把那条笔记扔掉"),
            Sample("Lupakan nomor lama saya", true, "忘掉我的旧号码"),
            Sample("Hilangkan catatan itu", true, "把那条笔记去掉"),
            Sample("Jangan hapus catatan alamat", false, "别删地址笔记"),
            Sample("Jangan lupakan alamat rumah saya", false, "别忘了我家的地址"),
            Sample("Biarkan saja", false, "就这样吧"),
            Sample("Tidak usah dihapus", false, "不用删"),
            Sample("Kalau aku minta kamu menghapus catatan ini, apa yang akan kamu lakukan?", false, "要是我请你删掉这条笔记,你会怎么做"),
            Sample("Bagaimana jika saya minta kamu menghapus catatan ini?", false, "要是我请你删掉这条笔记,怎么样"),
            Sample("Kalau aku bilang hapus, gimana caranya?", false, "要是我说删掉,要怎么做"),
            Sample("Jangan di-delete catatan itu", false, "那条笔记别删除"),
            // 「juga」(也)词尾的 ga 不算否定;enggak、ngga、ngak、kagak 照旧算
            Sample("Catatan alamat lama juga perlu dihapus", true, "旧地址那条笔记也要删"),
            Sample("Catatan itu tidak perlu dihapus", false, "那条笔记不用删"),
            Sample("Catatan itu ga usah dihapus", false, "那条笔记不用删"),
            Sample("Catatan itu enggak usah dihapus", false, "那条笔记不用删"),
            Sample("Catatan itu ngak usah dihapus", false, "那条笔记不用删"),
            Sample("Catatan itu kagak usah dihapus", false, "那条笔记不用删"),
            // 「tunjangan」(津贴)词尾的 jangan 不算否定
            Sample("Catatan soal tunjangan dihapus aja", true, "津贴那条笔记删了吧"),
            Sample("Hapus catatan gaji lama, tunjangan diubah jadi lima juta", true, "删掉旧工资那条笔记,津贴改成五百万了"),
            Sample("Hapus catatan ini, tunjangan diapa-apakan pun saya tidak peduli", true, "删掉这条笔记,津贴怎么处理我都不管"),
            Sample("Catatan itu jangan dihapus", false, "那条笔记别删"),
            Sample("Hapus? Jangan diapa-apakan dulu", false, "删?先什么都别动"),
        )
    }

    // 句子里夹着英文动词(jangan edit、update etme、保存しないで):中英文块认得这个动词,本语言的否定和「只是问问」照样把它否掉
    @Test fun englishVerbsInsideAnotherLanguage() {
        assertRemember(
            // 夹用英文动词时,本语言的否定照样算数(中英文块认得 edit / save,认不得 jangan / しないで)
            Sample("この設定は保存しないで", false, "这个设置别保存(「保存」中英文块也认得)"),
            Sample("Jangan save alamat ini", false, "这个地址别保存"),
            Sample("Bunu save etme", false, "这个别保存"),
            Sample("Đừng save số này", false, "这个号码别保存"),
        )
        assertEdit(
            "update",
            Sample("Jangan edit catatan itu", false, "那条笔记别改"),
            Sample("Bunu update etme", false, "这个别更新"),
        )
        assertEdit(
            "delete",
            Sample("Jangan delete catatan itu", false, "那条笔记别删"),
        )
    }

    // 每句话都要过所有语言块:西语、葡语、土耳其语的词干不能把英文常用词当成让它记、让它改删(quite、tired、silent、editor……)
    @Test fun englishWordsAreNotReadAsAnotherLanguage() {
        val english = listOf(
            "I quite like this one", "I'm tired today", "show me the entire list", "my mom retired last year", "the satire show",
            "the attire is formal", "a tiresome job", "put it on silent", "the silver car", "silly question", "silicon valley",
            "exclusive offer", "the editor is open", "the editorial page", "the project was funded", "it was altered",
            "fusion cuisine", "a guarded answer", "register for the event", "I registered yesterday", "salvage the file",
            "it is recordable", "the exchange cambio rate",
        )
        for (s in english) {
            assertEquals("[delete]「$s」", false, MemoryRequests.requested("delete", s, listOf(s)))
            assertEquals("[update]「$s」", false, MemoryRequests.requested("update", s, listOf(s)))
            assertRemember(Sample(s, false, "英文常用词"))
        }
        // 汉字同形:中文的「除去」是「除了」,日文的「を除いて」是「除了」,「短く答えて」是答短点,都不是让它改删
        assertEdit("delete", Sample("除去周末以外都提醒我", false, "除了周末"), Sample("これを除いて全部教えて", false, "除了这个全告诉我"))
        assertEdit("update", Sample("短く答えて", false, "回答短一点"))
    }

    /** 界面每种语言(app 的 values-<语言> 目录,加上默认的英文)都有一块说法;加了界面语言没加说法,或反过来,这里会提醒。 */
    @Test fun everyUiLanguageHasABlock() {
        val res = File("../app/src/main/res")
        assertTrue("找不到 ${res.canonicalPath}", res.isDirectory)
        val ui = res.listFiles().orEmpty().map { it.name }.filter { it.startsWith("values-") }
            .map { it.removePrefix("values-").removePrefix("b+").substringBefore('+').substringBefore("-r") }
            .filter { it.length in 2..3 }
            .map { if (it == "in") "id" else it }
            .toSet() + "en"
        assertEquals(ui.sorted(), MemoryRequests.languages.distinct().sorted())
    }
}
