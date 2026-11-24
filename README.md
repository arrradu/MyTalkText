# TalkText 0.20

Dictare în orice aplicație de pe Android, ca Wispr Flow, dar cu cheile tale:
**voce → OpenAI (transcriere) → Claude (corectare) → text la cursor.**

Fostul „AdiFlow” 0.1 (făcut de GPT) a fost rescris complet și redenumit. Sursele lui vechi sunt în `../AdiFlow-source.zip`, iar mapa `../AdiFlow` poate fi ștearsă.

## Cum se folosește
1. Atingi câmpul în care scrii (WhatsApp, Telegram, Gmail etc.). Apare **bula lila** cu bare de undă.
2. Atingi bula. Barele pulsează cât pornește microfonul, apoi bula devine **neagră**, cu barele care se mișcă după vocea ta și cu un cronometru. Acum vorbești, cât vrei, cu pauze.
3. Atingi bula din nou. Apar trei puncte cât se procesează textul, apoi textul apare în câmp, la cursor.
4. Timp de 2 secunde bula arată **↶**: dacă o atingi, inserarea se anulează.

- **Limba și traducerea prin voce:** implicit româna. Dacă începi cu „**Engleză**, …” (sau „în engleză”), vorbești în română, iar în câmp apare textul **în engleză**, deja îmbunătățit; la fel „**Rusă**, …” / „pe rusește” pentru rusă. Dacă chiar vorbești în engleză sau rusă, începe cu „English, …” sau „ruski, …”. Cuvântul de comandă nu apare în text.
- **Punctuația urmează intonația:** când vocea urcă la final apare semnul întrebării („Ai trimis oferta?”), la pauzele scurte virgulă, la cele lungi punct, la accent semnul exclamării. Claude păstrează semnele astea și adaugă doar virgulele cerute de gramatică. Poți și dicta semnele: „virgulă”, „punct”, „semnul întrebării”, „rând nou”.
- **Anulare:** ții apăsat pe bula neagră. Dacă ții apăsat pe bula lila, se deschide aplicația.
- **Bula se poate muta:** o tragi oriunde; poziția se păstrează. Dispare imediat ce ieși din aplicația în care ai dictat (rămâne doar cât timp ascultă sau procesează, ca s-o poți opri).
- **Nu apasă niciodată Trimite.** Nu apare în câmpurile de parolă și nici în aplicațiile pe care le treci în lista de excluse.

## Tastatura TalkText (de la 0.8)
O tastatură proprie, în stilul Gboard, cu două butoane în bara de sus:
- **Butonul lila din dreapta sus: dictare fără bulă.** Se poartă exact ca bula, doar că stă în tastatură: atingi, devine negru cu bare care se mișcă după voce (tastele rămân pe loc), atingi din nou ca să termini, apar trei puncte cât se scrie, apoi textul e la cursor. Vocea trece prin OpenAI, apoi prin Claude. Timp de 2 secunde butonul arată **↶**, iar o atingere anulează. Dacă ții apăsat cât ascultă, anulezi dictarea. Cât timp tastatura TalkText e activă, bula nu apare.
- **文A stânga sus: traducere în timp ce scrii.** Scrii în română în caseta din tastatură, iar în câmp apare direct traducerea în engleză (Claude Haiku, după o pauză scurtă). **Enter** fixează fraza, **✕** închide. Limbile (română / engleză / rusă) se schimbă din butoanele lila, ⇄ le inversează. Poți și dicta în modul traducere: spui în română, primești în engleză.
- **Tastele:** QWERTY românesc, cu ă â î ș ț la apăsare lungă pe a, i, s, t; cifrele la apăsare lungă pe rândul de sus; rusă ЙЦУКЕН cu 🌐; două pagini de simboluri; Shift, iar dublă atingere = Caps Lock; majusculă automată la început de propoziție; ștergere continuă; glisare pe spațiu ca să muți cursorul; două spații = „. ”. Ține apăsat pe 🌐 ca să treci înapoi la Gboard.
- **Aspect:** ca Gboard fără margini: literele stau direct pe fundal, simbolurile mici în colțul tastelor (a @, s #, … m ?) se scriu la apăsare lungă, iar ?123, Enter și spațiul sunt forme rotunjite. Microfonul e rotund; cât ascultă, devine lila, cu bare care urmează vocea. Apăsarea unei taste se stinge lin, iar previzualizarea literei crește ușor.
- **Corectare automată și sugestii** (de la 0.11), pe telefon, fără internet: trei sugestii în bara de sus cât scrii; la spațiu sau punctuație se corectează greșelile de tastare și se pun diacriticele („si” → „și”, „sa” → „să”, „in” → „în”, „multumesc” → „mulțumesc”; „ca” și „casa” rămân, pentru că sunt și ele corecte). Româna și engleza merg amestecate: limba frazei se ghicește din cuvintele dinainte. Rusa are tastatura ei. **Backspace imediat după o corectare** o anulează și învață cuvântul. Cuvintele din Dicționar nu sunt corectate niciodată. Se pot opri din Setări → Tastatura. **Cuvintele din Dicționar** sunt și ținte de corectare: „ruseste” → „rusește”, „artinx” → „Artinox” (cu majuscula pe care ai scris-o). Listele de cuvinte (210 000 RO, 50 000 EN, 90 000 RU) provin din **wordfreq** (Robyn Speer), iar româna e completată cu formele flexionate din **FrequencyWords** (Hermit Dave, subtitrări OpenSubtitles); ambele au licență CC BY-SA 4.0; scriptul care le generează este `tools/lexgen.py`.
- **✨ Îmbunătățește (lângă traducere):** trimite la Claude textul deja scris în câmp, sau doar porțiunea selectată, și îți arată o variantă corectată și mai clar formulată, în aceeași limbă (română, rusă, engleză), fără să adauge conținut. Alegi **Înlocuiește** sau **Renunță**. Dacă între timp ai modificat textul, nu se înlocuiește nimic: varianta nouă se copiază.
- **📋 Clipboard (lângă microfon):** ultimele 20 de texte copiate, cel mai nou primul; atingi unul ca să-l lipești, ții apăsat ca să-l ștergi, „Șterge tot” golește lista. Ce aplicațiile marchează ca sensibil (parole, coduri) nu se salvează. Lista stă doar pe telefon; Android o completează doar cât TalkText e tastatura activă.
- Cât scrii un cuvânt, ✨ și 📋 se ascund ca să încapă sugestiile, ca la Gboard.
- **Ștergere prin glisare (ca la Gboard):** ții apăsat pe ⌫ și tragi spre stânga; la fiecare ~1 cm se mai selectează un cuvânt (vezi în câmp ce se va șterge), iar când ridici degetul se șterge. Tragi înapoi spre dreapta până nu mai e nimic selectat ca să renunți. O atingere scurtă pe ⌫ șterge o literă la ridicarea degetului; ținut apăsat, șterge continuu.
- **Textul copiat recent** apare câteva minute în mijlocul barei de sus (cu iconița 📋 și începutul textului sau al linkului): o atingere îl lipește, ținut apăsat îl ascunde.
- **Enter ca la Gboard:** în bara de căutare Google și oriunde aplicația cere o acțiune, Enter devine lupă / trimite / gata și o execută; rând nou pune doar unde aplicația cere rând nou.
- **Revenire la cuvântul tău:** dacă autocorectorul a schimbat un cuvânt și observi mai târziu, pune cursorul pe el: în bară apare forma scrisă de tine; o atingi și cuvântul revine (și nu-l mai corectează).
- **Învață cum scrii** (Setări → Tastatura → Învață cum scriu): ține minte cuvintele tale și cât de des le folosești (după două folosiri nu le mai corectează și ți le sugerează primele), cum le scrii, inclusiv cu majusculă („artinox” → „Artinox”), și ce cuvânt urmează de obicei după altul, ca să ți-l propună imediat după spațiu („compania” → „Artinox”). Învață și din dictări, iar numele învățate ajută și transcrierea vocii. Totul stă doar pe telefon (`personal.txt`); „Uită tot ce a învățat” șterge.
- **Despre mine** (tabul Dicționar): câteva rânduri despre tine (compania, mașina, colegii, proiectele) pe care Claude le folosește la dictare, traducere și îmbunătățire ca să înțeleagă la ce te referi; nu le adaugă niciodată în text.
- **Punctuație lipită de cuvânt:** după ce alegi o sugestie (care pune singură spațiul), virgula, punctul, „!” sau „?” se lipesc de cuvânt, iar spațiul trece după semn: „cuvânt, ”. Merge și pentru mai multe semne la rând („!!!”, „?!”). Un spațiu apăsat imediat după sugestie nu se dublează.
- **Liste numerotate** (Setări → Tastatura): un rând început cu „1.” sau „1)” și spațiu primește un spațiu în față; fiecare Enter lasă un rând liber și pune numărul următor („ 2.”, „ 3.”…), iar Enter pe un punct gol încheie lista. Rândurile lungi nu pot fi aliniate sub text: aplicația de chat le rupe singură. Funcționează în câmpurile pe mai multe rânduri (mesaje, e-mail), nu în căutări.
- **Vibrație la mutarea cursorului:** când glisezi pe bara de spațiu, fiecare pas al cursorului se simte printr-o vibrație scurtă (dacă vibrația e pornită).
- **Greșeli mai grele:** două cuvinte lipite, cu spațiul nimerit pe c / v / b / n / m sau sărit („transformatvautomat” → „transformat automat”, „vorbimnmaine” → „vorbim mâine”), și cuvinte cu două greșeli („inbunatatime” → „îmbunătățim”) sunt corectate sau apar în sugestii. Un cuvânt readus o dată (Backspace sau forma ta din bară) nu mai e corectat ca greșeală, dar primește în continuare diacriticele („sa” → „să”); readus de două ori, rămâne exact cum îl scrii.
- **Bara de spațiu prinde și partea de jos a tastelor c v b n m** (de la 0.20), ca la Gboard: o atingere puțin prea sus, care viza spațiul, nu mai scrie „b” sau „n”. Cuvintele lipite așa sunt corectate și când sunt trei („vezibsinsingur” → „vezi și singur”, „mergebchiarvok” → „merge chiar ok”), iar la o despărțire făcută pe c / v / b / n / m sunt acceptate și cuvinte mai rare („suntbsetate” → „sunt setate”, „Eubtiparesc” → „Eu tipăresc”).
- **Verificator ortografic (liniuțele roșii):** liniuțele roșii din Telegram, WhatsApp etc. vin de la verificatorul telefonului, nu de la tastatură. TalkText poate fi acel verificator: română întâi, engleza acceptată, rusa separat, plus Dicționarul tău și ce a învățat de la tine. Atingi un cuvânt subliniat și primești sugestiile TalkText („sa” → „să”, „sa-mi” → „să-mi”). Activare: Setări → Tastatura → Verificator ortografic → **Alege TalkText** (în Android: Verificator ortografic → TalkText, limba Română).
- **Cifre:** după un număr, punctul („25.”) te readuce la litere cu majuscula pornită; la fel, spațiul te readuce la litere. „!” și „?” te lasă pe pagina de simboluri, ca să poți pune mai multe la rând; înapoi la litere treci tu, cu ABC. În câmpurile numerice (telefon, sume) tastatura rămâne pe cifre.
- **Capturi de ecran:** dacă faci o captură (în ultimele 3 minute) și deschizi tastatura, în mijlocul barei de sus apare miniatura ei: o atingi și imaginea se pune în chat; ții apăsat ca s-o ascunzi. Dacă aplicația nu primește imagini de la tastatură, captura se copiază și o lipești. Pentru asta, TalkText → Setări → Tastatura → Capturi de ecran → **Permite** („Allow all”).
- **Încă lipsesc:** scriere prin glisare, emoji.
- **Activare:** TalkText → Acasă → pasul **Tastatura TalkText** → Activează (se deschide lista de tastaturi: pornești TalkText) → **Alege**.

## Design
Stil apropiat de Wispr Flow, dar cu identitate proprie (fără logo-ul sau imaginile lor): fundal crem, titluri serif (**EB Garamond**), text **Figtree**, accent lila. Ambele fonturi au licență SIL Open Font License 1.1. Ecranele sunt **Acasă** (pașii de pregătire, statistici: cuvinte dictate, minute vorbite și câștigate, câmp de test, dictări recente), **Dicționar**, **Expresii** și **Setări** (chei, corectare Claude, limba implicită, aplicații fără bulă, modelul Claude ales din trei butoane: Haiku, Sonnet, Opus).

## Cum se evită pierderile
- Microfonul înregistrează continuu, fără reporniri. Audio se taie în bucăți doar în pauze: prima bucată are 6–20 s, următoarele 40–90 s. Fiecare bucată se transcrie **cât timp tu încă vorbești**, așa că la final aștepți doar ultima bucată.
- Fiecare bucată se salvează pe telefon până când transcrierea reușește. Dacă pică internetul, audio rămâne salvat: pe pagina Acasă din TalkText apare cardul **Reîncearcă**.
- Dacă Claude dă eroare, se inserează textul necorectat, fără niciun mesaj peste aplicația în care scrii; motivul apare ca un card pe pagina Acasă din TalkText. Dacă textul lui Claude are mai puțin de jumătate sau aproape dublul lungimii originalului (adică a rezumat sau a răspuns în loc să corecteze), se inserează originalul.
- Dacă nu se poate insera, textul e copiat în clipboard. Ultimele 40 de dictări rămân în TalkText → Acasă → **Recente**: atingi una și o copiezi.
- Durata maximă a unei dictări: 30 de minute.

## Instalare pe Pixel
1. Dacă ai instalat vechiul **AdiFlow**, dezinstalează-l. Copiază **`TalkText-debug.apk`** pe telefon și instalează-l (Files → Descărcări → atinge fișierul → Instalează).
2. Deschide TalkText. Pe pagina Acasă, cardul **„Hai să pornim”** are 5 pași; fiecare primește ✓ când e gata, iar cardul dispare când toți sunt gata:
   - Microfon, Notificări.
   - **Bula (Accesibilitate)**: butonul **Deschide** → TalkText → On. Dacă scrie **„Controlled by Restricted Setting”**: butonul **Deblochează** (sau Settings → Apps → TalkText) → **⋮** (sus, dreapta) → **Allow restricted settings** → confirmi cu amprenta sau PIN-ul, apoi din nou **Deschide** → On.
   - **Cheile**, în tabul **Setări**: cheia OpenAI (obligatorie) de la platform.openai.com → API keys și cheia Anthropic (pentru corectare) de la console.anthropic.com → API Keys.
   - Tabul **Dicționar**: numele și termenii tăi (Artinox, Molinart, nume de clienți). Ajută și transcrierea, și corectarea. Tabul **Expresii**: scurtături de tipul „semnătura” → textul semnăturii întregi.
3. Încearcă în câmpul **„Încearcă aici”** de pe pagina Acasă, apoi în WhatsApp.

Versiunile noi se instalează peste cea veche, ca actualizare; setările și cheile rămân.

## Cost aproximativ
Transcrierea OpenAI costă câțiva cenți pe 10 minute de dictare. Corectarea Claude Haiku costă sub un cent pentru un mesaj obișnuit. Modelele se pot schimba la secțiunea 4 din aplicație.

## Confidențialitate
- Spre **OpenAI** pleacă doar audio dictării, iar spre **Anthropic** doar textul transcris, dicționarul și expresiile. Conținutul ecranului și textul deja scris în câmp nu pleacă nicăieri.
- Cheile sunt criptate cu Android Keystore. Nu există backup, telemetrie sau server intermediar.

## Ce NU s-a putut verifica aici
APK-ul s-a compilat și testele de logică trec, dar **nu a rulat încă pe un telefon**: aici nu există emulator și nici telefon conectat. De testat pe Pixel:
1. Pornirea microfonului din bulă. Dacă Android blochează microfonul din fundal, aplicația deschide automat o fereastră invizibilă pentru o fracțiune de secundă (tastatura poate clipi). Dacă tot nu merge, apare un mesaj.
2. Inserarea în WhatsApp, Telegram, Gmail, Chrome, inclusiv la mijlocul unui text existent, plus butonul ↶.
3. O dictare de 3–5 minute cu pauze și o dictare pe date mobile slabe sau în modul avion (trebuie să apară Reîncearcă).
4. „Engleză, …” / „Rusă, …” (traducere) și „English, …” / „ruski, …” la început.

## Compilare
`python build_local.py` (de lucru) sau `python build_local.py --release` (arhivează în `../Versiuni`, fără să suprascrie o versiune existentă): rulează testele de logică (`tests/RulesTest.java`), apoi aapt2 → javac → D8 → zipalign → apksigner, fără Gradle. Folosește SDK-ul și JDK-ul din `Lynk&Co900_engeneering/.../tools`. Proiectul se poate deschide și în Android Studio (`build.gradle`).

## Structura codului (`app/src/main/java/ro/talktext`)
| Fișier | Rol |
|---|---|
| `KeyboardService`, `KeyboardView`, `KeyLayout` | tastatura: taste, traducere, dictare din microfon (butonul e bula, în mic) |
| `InsertService` | bula, câmpul țintă, inserarea la cursor, anularea |
| `Bubble` | desenul bulei și stările ei |
| `RecorderService` | înregistrarea continuă (serviciu în prim-plan) |
| `Chunker`, `Wav` | tăierea audio în pauze, formatul WAV |
| `Session` | ordinea bucăților, limba, Claude, reîncercarea |
| `Transcriber`, `ClaudeClient`, `Http` | apelurile OpenAI și Anthropic, cu reîncercări |
| `Rules`, `Spacing` | comanda de limbă, spațierea la inserare |
| `Lexicon`, `Corrector` | listele de cuvinte, corectarea automată și sugestiile |
| `SpellService`, `Words` | verificatorul ortografic al telefonului; listele de cuvinte comune tastaturii și verificatorului |
| `Personal`, `PersonalStore` | ce învață din felul tău de a scrie (cuvinte, majuscule, cuvântul următor) |
| `MainActivity`, `Ui` | ecranele (Acasă, Dicționar, Expresii, Setări) și stilul lor |
| `StartActivity` | pornirea de rezervă a microfonului |
| `Prefs`, `Vault` | setările, istoricul și statisticile; cheile criptate |
