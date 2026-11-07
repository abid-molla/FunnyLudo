# FunnyLudo (Android)

Offline Ludo for 2 to 6 players with 3D-style graphics, animations and meme sounds. Pure Kotlin, no libraries.

## Build
- Android Studio: open this folder, press Run, or Build > Build APK(s).
- Terminal (Android SDK installed): `gradle assembleDebug`, APK at `app/build/outputs/apk/debug/app-debug.apk`.
- GitHub: push this folder; the workflow in `.github/workflows/build.yml` builds the APK. Download it from the Actions tab (artifact `FunnyLudo-apk`).

## Menu
Quick Play (you vs 3 bots, 2 pawns each), Classic (choose 2-6 players and who is human or bot), Play with Friends (2-6 humans on one phone), Play with Bots (you vs bots), Sound on/off.
Empty seats stay empty: no bots are added. 2-4 players use the classic cross board, 5-6 players use a six-armed star board.

## Sounds (res/raw)
| Event | Sound |
|---|---|
| Cut an opponent's pawn | random: please-khoma-kore-dau, fart-funk, aayein-meme |
| Roll a 6 | random: tudun-tadau, rag-korla |
| Land on a star / safe cell | random: safe-3, duck-toy-sound |
| Pawn enters home | random: 7-crore-kbc, nahi-nahi-saluke-yaha-kuchh-to-gadbad-hai |
| Win the game | hum-jeet-gaye |
| Can't move / three 6s | fahhhh |
| Dice rattle, step pop, leaving the yard | small synthesized sounds (`tools/gen_sounds.py`) |

Long clips were trimmed (cut sounds to 5 s, tudun-tadau to 3.5 s). To use other audio, replace the files in `app/src/main/res/raw/` keeping the names.

## Icon
`tools/gen_icon.py` draws the launcher icon (needs Pillow).
