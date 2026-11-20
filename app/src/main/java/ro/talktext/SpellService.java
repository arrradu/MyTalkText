package ro.talktext;

import android.service.textservice.SpellCheckerService;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * TalkText as the phone's spell checker (the red underlines in Telegram, WhatsApp…): Romanian first,
 * English words accepted, Russian on its own; your dictionary and what TalkText learned from you count as correct.
 * Tapping an underlined word shows our suggestions: "sa" → "să", "sa-mi" → "să-mi".
 */
public class SpellService extends SpellCheckerService {
    @Override public Session createSession() { return new Checker(); }

    private final class Checker extends Session {
        private final Corrector corrector = new Corrector();
        private boolean russian;

        @Override public void onCreate() {
            String locale = getLocale();
            russian = locale != null && locale.startsWith("ru");
            corrector.setUserWords(Prefs.lines(Prefs.dictionary(SpellService.this)),
                    Prefs.lines(Prefs.get(SpellService.this).getString("kb_learned", "")));
            if (PersonalStore.enabled(SpellService.this)) {
                Personal me = PersonalStore.get(SpellService.this);
                corrector.setPersonal(me.lexicon(2), me.capitalized(3));
            }
        }

        @Override public SuggestionsInfo onGetSuggestions(TextInfo info, int limit) {
            String text = info.getText() == null ? "" : info.getText().trim();
            // Words only: numbers, links, e-mails and symbols are never "wrong".
            if (text.isEmpty() || !text.codePoints().allMatch(cp -> Character.isLetter(cp) || cp == '-' || cp == '\'' || cp == '’'))
                return ok(info);
            Lexicon primary = russian ? Words.ru(SpellService.this) : Words.ro(SpellService.this); // first call loads the list
            Lexicon secondary = russian ? null : Words.en(SpellService.this);
            if (primary == null) return ok(info);
            // "sa-mi": check every part, keep the hyphens ("să-mi").
            String[] parts = text.split("-", -1);
            StringBuilder fixed = new StringBuilder();
            boolean wrong = false;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                String better = part.length() < 2 ? null : corrector.correct(part, primary, secondary);
                if (better == null && part.length() >= 2 && !corrector.known(part, primary, secondary)) wrong = true;
                if (better != null) wrong = true;
                fixed.append(i > 0 ? "-" : "").append(better == null ? part : better);
            }
            if (!wrong) return ok(info);
            List<String> out = new ArrayList<>();
            if (!fixed.toString().equals(text)) out.add(fixed.toString());
            if (parts.length == 1)
                for (String s : corrector.suggest(text, primary, secondary)) if (!s.equals(text) && !out.contains(s)) out.add(s);
            if (out.size() > Math.max(1, limit)) out = out.subList(0, Math.max(1, limit));
            int flags = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO | (out.isEmpty() ? 0 : SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS);
            return new SuggestionsInfo(flags, out.toArray(new String[0]), info.getCookie(), info.getSequence());
        }

        private SuggestionsInfo ok(TextInfo info) {
            return new SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, new String[0], info.getCookie(), info.getSequence());
        }
    }
}
