# -*- coding: utf-8 -*-
"""重新生成陆战棋裁决语音：0..4 = 玩家A(男声云希)，5..9 = 玩家B(女声晓晓)。
文案与 MainActivity.kt companion object 中的列表严格一致。
"""
import asyncio
import edge_tts

VOICE_A = "zh-CN-YunxiNeural"      # 玩家 A：男声
VOICE_B = "zh-CN-XiaoxiaoNeural"   # 玩家 B：女声
OUT_DIR = r"E:\pad\app\src\main\res\raw"

TEXTS = {
    "tts_attacker_won": [
        "哈哈，我的子比你大，你被吃啦！", "以卵击石，不自量力！", "太弱了，不堪一击啊！", "碾压局，轻松拿下！", "哎呀，不好意思，把你吃掉了。",
        "兄弟，看清楚军衔再撞好吗？", "大鱼吃小鱼，我吃你！", "哇哦，白给的一个子，谢谢啦！", "就这？还想跟我硬碰硬？", "实力悬殊，我赢麻了！",
    ],
    "tts_defender_won": [
        "哎呦喂，自己撞枪口上啦？", "飞蛾扑火，自寻死路！", "防守反击，就是这么轻松！", "撞墙了吧？头破血流了吧！", "真是不长眼啊，直接送人头！",
        "哈哈，这可是铁板一块，撞不破的！", "守株待兔，你果然上钩了！", "感谢老铁送来的棋子！", "想偷袭？没门！我比你大！", "完美防御，反杀成功！",
    ],
    "tts_both_died": [
        "卧槽，居然同归于尽了！", "啊！一起毁灭吧！", "炸弹！砰的一声，什么都没了！", "玉石俱焚，谁也别想好过！", "双双把家还，这也太惨烈了！",
        "硬碰硬，结果大家都没了！", "旗鼓相当，一换一不亏！", "你不要过来啊！轰！同归于尽！", "这是什么神仙对决，直接双杀！", "得，这下大家都清净了。",
    ],
    "tts_flag_captured": [
        "拔得头筹！你的军旗被我扛走啦！", "军旗倒下，游戏结束！你输了！", "哈哈，你的大本营被我端啦！", "夺旗成功！胜利属于我！", "这军旗我就笑纳了，承让承让！",
        "大势已去，你的军旗没保住啊！", "直捣黄龙，军旗到手！", "一招毙命，摘下你的军旗！", "防守太差了吧，军旗就这么被我拿了？", "绝杀！夺取军旗，游戏通关！",
    ],
}


async def gen(prefix: str, index: int, text: str) -> None:
    voice = VOICE_A if index < 5 else VOICE_B
    out = rf"{OUT_DIR}\{prefix}_{index}.mp3"
    await edge_tts.Communicate(text, voice, rate="+10%").save(out)
    print(f"OK {prefix}_{index}.mp3 [{voice}] {text}")


async def main() -> None:
    for prefix, texts in TEXTS.items():
        for i, text in enumerate(texts):
            await gen(prefix, i, text)


if __name__ == "__main__":
    asyncio.run(main())
