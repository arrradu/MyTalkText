# Componente preluate și licențele lor

| Ce | Unde în proiect | Sursa | Licență |
|---|---|---|---|
| Fonturile EB Garamond și Figtree | `app/src/main/res/font/` | Google Fonts | SIL Open Font License 1.1 |
| Liste de cuvinte (frecvențe) RO / EN / RU | `app/src/main/res/raw/lex_*.txt` | [wordfreq](https://github.com/rspeer/wordfreq) (Robyn Speer); româna completată din [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (Hermit Dave, OpenSubtitles 2018) | CC BY-SA 4.0 (listele derivate rămân sub aceeași licență) |
| Iconițe (shift, ștergere, enter, glob, microfon, traducere, clipboard, ✨) | `app/src/main/res/drawable/` | Material Icons (Google) | Apache License 2.0 |

Scriptul care generează listele de cuvinte este `tools/lexgen.py`.

Nu fac parte din acest depozit: cheia de semnare (`debug.keystore`), APK-urile compilate și orice cod sau APK al altor aplicații (Wispr Flow, Gboard), folosite doar ca referință locală.
