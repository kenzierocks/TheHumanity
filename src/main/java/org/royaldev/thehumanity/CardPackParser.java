package org.royaldev.thehumanity;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.royaldev.thehumanity.cards.CardPack;
import org.royaldev.thehumanity.cards.types.BlackCard;
import org.royaldev.thehumanity.cards.types.WhiteCard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * A parser for CardPack files.
 */
public class CardPackParser {

    @NotNull
    private final TheHumanity humanity;

    public CardPackParser(@NotNull final TheHumanity humanity) {
        this.humanity = humanity;
    }

    /**
     * Parses one CardPack given the name of the file that contains it. If there is any IOException while processing, or
     * if the file cannot be read, null will be returned.
     *
     * @param name Name of the file the CardPack is contained in
     * @return CardPack or null
     */
    @Nullable
    public CardPack parseCardPack(@NotNull final String name) {
        final File f = new File("cardpacks", name);
        if (!f.exists() || !f.isFile()) {
            this.humanity.getLogger().warning(f.getName() + " does not exist.");
            return null;
        }
        if (!f.canRead()) {
            this.humanity.getLogger().warning("Cannot read " + f.getName() + ".");
            return null;
        }
        final CardPack cp = new CardPack(CardPack.getNameFromFileName(f.getName()));
        try (final BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            ParseStage ps = ParseStage.METADATA;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                ps = ps.parse(cp, line);
            }
        } catch (final IOException ex) {
            this.humanity.getLogger().warning(ex.getMessage());
            return null;
        }
        return cp;
    }

    /**
     * Returns a Collection of CardPacks given an array of their file names. This calls the {@link #parseCardPack}
     * method, but all null results are filtered out of the returned Collection.
     *
     * @param names Array of names of files CardPacks are contained in
     * @return Collection not containing null
     */
    @NotNull
    public Collection<CardPack> parseCardPacks(@NotNull final String[] names) {
        return Arrays.stream(names).map(this::parseCardPack).filter(cp -> cp != null).collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Stages of parsing for {@link org.royaldev.thehumanity.CardPackParser}.
     */
    private static enum ParseStage {
        /**
         * Parsing the metadata section.
         */
        METADATA("___METADATA___") {
            @NotNull
            @Override
            ParseStage parseInternal(@NotNull final CardPack cp, @NotNull final String line) {
                final String[] parts = line.split("\\s*:\\s*");
                if (parts.length < 2) return this;
                final String key = parts[0];
                final String value = StringUtils.join(parts, ' ', 1, parts.length);
                switch (key.toLowerCase()) {
                    case "description":
                        cp.setDescription(value);
                        break;
                    case "author":
                        cp.setAuthor(value);
                        break;
                }
                return this;
            }
        },
        /**
         * Parsing the black cards section.
         */
        BLACK_CARDS("___BLACK___") {
            @NotNull
            @Override
            ParseStage parseInternal(@NotNull final CardPack cp, @NotNull final String line) {
                cp.addCard(new BlackCard(cp, line));
                return this;
            }
        },
        /**
         * Parsing the white cards section.
         */
        WHITE_CARDS("___WHITE___") {
            @NotNull
            @Override
            ParseStage parseInternal(@NotNull final CardPack cp, @NotNull final String line) {
                cp.addCard(new WhiteCard(cp, line));
                return this;
            }
        };

        private final String header;

        ParseStage(final String header) {
            this.header = header;
        }

        /**
         * Gets the matching ParseStage from a given header line. If there is no matching header, null will be returned.
         *
         * @param line Header line to match
         * @return ParseType or null if no matching header
         */
        @Nullable
        static ParseStage getHeaderType(@NotNull final String line) {
            for (final ParseStage ps : ParseStage.values()) {
                if (ps.getHeader().equals(line)) {
                    return ps;
                }
            }
            return null;
        }

        @NotNull
        abstract ParseStage parseInternal(@NotNull final CardPack cp, @NotNull final String line);

        /**
         * Gets the header of this section (e.g. "___BLACK___"). The header declares the start of a new section in a
         * CardPack file.
         *
         * @return Header
         */
        @NotNull
        String getHeader() {
            return this.header;
        }

        /**
         * Parses one line into the given CardPack. This will return the next ParseStage to use for correct parsing.
         *
         * @param cp   CardPack to parse for
         * @param line Line to parse
         * @return The next ParseStage, never null
         */
        @NotNull
        ParseStage parse(@NotNull final CardPack cp, @NotNull final String line) {
            final ParseStage headerType = ParseStage.getHeaderType(line);
            if (headerType != null) {
                return headerType;
            }
            return this.parseInternal(cp, line);
        }
    }

}
