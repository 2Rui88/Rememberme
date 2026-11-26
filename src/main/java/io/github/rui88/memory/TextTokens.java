package io.github.rui88.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

@Component
public final class TextTokens {
    private final Encoding encoding;

    public TextTokens(@Value("${memory.token-encoding:CL100K_BASE}") EncodingType encodingType) {
        encoding = Encodings.newLazyEncodingRegistry().getEncoding(encodingType);
    }

    public int count(String text) {
        return encoding.countTokensOrdinary(text);
    }

    public String truncate(String text, int budget) {
        if (budget <= 0) {
            return "";
        }
        if (count(text) <= budget) {
            return text;
        }
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int middle = (low + high + 1) / 2;
            int end = text.offsetByCodePoints(0, middle);
            if (count(text.substring(0, end)) <= budget) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, text.offsetByCodePoints(0, low));
    }
}
