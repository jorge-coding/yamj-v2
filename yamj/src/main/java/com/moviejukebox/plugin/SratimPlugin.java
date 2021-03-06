/*
 *      Copyright (c) 2004-2016 YAMJ Members
 *      https://github.com/orgs/YAMJ/people
 *
 *      This file is part of the Yet Another Movie Jukebox (YAMJ) project.
 *
 *      YAMJ is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU General Public License as published by
 *      the Free Software Foundation, either version 3 of the License, or
 *      any later version.
 *
 *      YAMJ is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU General Public License for more details.
 *
 *      You should have received a copy of the GNU General Public License
 *      along with YAMJ.  If not, see <http://www.gnu.org/licenses/>.
 *
 *      Web: https://github.com/YAMJ/yamj-v2
 *
 */
package com.moviejukebox.plugin;

import com.moviejukebox.model.Library;
import com.moviejukebox.model.Movie;
import com.moviejukebox.model.MovieFile;
import com.moviejukebox.tools.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SratimPlugin extends ImdbPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(SratimPlugin.class);
    public static final String SRATIM_PLUGIN_ID = "sratim";
    public static final String SRATIM_PLUGIN_SUBTITLE_ID = "sratim_subtitle";
    private static final Pattern NFO_PATTERN = Pattern.compile("http://[^\"/?&]*sratim.co.il[^\\s<>`\"\\[\\]]*");
    private static final String[] GENRE_STRING_ENGLISH = {"Action", "Adult", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama",
        "Family", "Fantasy", "Film-Noir", "Game-Show", "History", "Horror", "Music", "Musical", "Mystery", "News", "Reality-TV", "Romance",
        "Sci-Fi", "Short", "Sport", "Talk-Show", "Thriller", "War", "Western"};
    private static final String[] GENRE_STRING_HEBREW = {"פעולה", "מבוגרים", "הרפתקאות", "אנימציה", "ביוגרפיה", "קומדיה", "פשע", "תיעודי", "דרמה", "משפחה", "פנטזיה",
        "אפל", "שעשועון", "היסטוריה", "אימה", "מוזיקה", "מחזמר", "מיסתורין", "חדשות", "ריאליטי", "רומנטיקה", "מדע בדיוני", "קצר", "ספורט", "אירוח",
        "מתח", "מלחמה", "מערבון"};
    private static boolean subtitleDownload = false;
    private static boolean keepEnglishGenres = false;
    private static boolean bidiSupport = true;
    private final int plotLineMaxChar;
    private final int plotLineMax;
    private final TheTvDBPlugin tvdb;
    private static String lineBreak;
    // Porting from my old code in c++
    public static final int BCT_L = 0;
    public static final int BCT_R = 1;
    public static final int BCT_N = 2;
    public static final int BCT_EN = 3;
    public static final int BCT_ES = 4;
    public static final int BCT_ET = 5;
    public static final int BCT_CS = 6;

    public SratimPlugin() {
        super(); // use IMDB if sratim doesn't know movie

        tvdb = new TheTvDBPlugin(); // use TVDB if sratim doesn't know series

        plotLineMaxChar = PropertiesUtil.getIntProperty("sratim.plotLineMaxChar", 50);
        plotLineMax = PropertiesUtil.getIntProperty("sratim.plotLineMax", 2);

        subtitleDownload = PropertiesUtil.getBooleanProperty("sratim.subtitle", Boolean.FALSE);
        keepEnglishGenres = PropertiesUtil.getBooleanProperty("sratim.KeepEnglishGenres", Boolean.FALSE);
        bidiSupport = PropertiesUtil.getBooleanProperty("sratim.BidiSupport", Boolean.TRUE);

        lineBreak = PropertiesUtil.getProperty("mjb.lineBreak", "{br}");
    }

    @Override
    public String getPluginID() {
        return SRATIM_PLUGIN_ID;
    }

    @Override
    public boolean scan(Movie movie) {
        boolean retval = false;

        String id = movie.getId(SRATIM_PLUGIN_ID);
        if (StringTools.isNotValidString(id)) {
            // collect missing information from IMDB or TVDB before sratim
            if (!movie.getMovieType().equals(Movie.TYPE_TVSHOW)) {
                retval = super.scan(movie);
            } else {
                retval = tvdb.scan(movie);
            }

            // Translate genres to Hebrew
            translateGenres(movie);

            id = getSratimId(movie);
        }

        if (StringTools.isValidString(id)) {
            retval = updateMediaInfo(movie);
        }

        return retval;
    }

    /**
     * retrieve the sratim id for the movie
     *
     * @param movie
     * @return
     */
    public String getSratimId(Movie movie) {

        try {
            String imdbId = updateImdbId(movie);

            if (StringTools.isNotValidString(imdbId)) {
                return Movie.UNKNOWN;
            }

            String xml = httpClient.request("http://www.sratim.co.il/browse.php?q=imdb%3A" + imdbId);

            Boolean missingFromSratimDB = xml.contains("לא נמצאו תוצאות העונות לבקשתך");
            if (missingFromSratimDB) {
                return Movie.UNKNOWN;
            }

            if (subtitleDownload) {
                String subtitlesID = HTMLTools.extractTag(xml, "<a href=\"subtitles.php?", 0, "\"");
                int subid = subtitlesID.lastIndexOf("mid=");
                if (subid > -1 && subtitlesID.length() > subid) {
                    String subtitle = subtitlesID.substring(subid + 4);
                    movie.setId(SRATIM_PLUGIN_SUBTITLE_ID, subtitle);
                }
            }

            String tmp_url = HTMLTools.extractTag(xml, "<div class=\"browse_title_name\"", "</div>");
            String detailsUrl = HTMLTools.extractTag(tmp_url, "<a href=\"view.php?", 0, "\"");
            if (StringTools.isNotValidString(detailsUrl)) {
                // try TV series view page
                detailsUrl = HTMLTools.extractTag(tmp_url, "<a href=\"viewseries.php?", 0, "\"");
            }
            if (StringTools.isNotValidString(detailsUrl)) {
                return Movie.UNKNOWN;
            }

            // update movie ids
            int id = detailsUrl.lastIndexOf("id=");

            if (id > -1 && detailsUrl.length() > id) {
                String movieId = detailsUrl.substring(id + 3);
                int idEnd = movieId.indexOf('&');
                if (idEnd > -1) {
                    movieId = movieId.substring(0, idEnd);
                }
                movie.setId(SRATIM_PLUGIN_ID, movieId);
                return movieId;
            }
        } catch (IOException error) {
            LOG.error("Failed retrieving sratim informations for movie: {} - {}", movie.getTitle(), error.getMessage());
        }

        return Movie.UNKNOWN;
    }

    // Translate IMDB genres to Hebrew
    protected void translateGenres(Movie movie) {
        if (keepEnglishGenres) {
            return;
        } else if (!OverrideTools.checkOverwriteGenres(movie, SRATIM_PLUGIN_ID)) {
            return;
        }

        Set<String> genresHeb = new TreeSet<>();

        // Translate genres to Hebrew
        for (String genre : movie.getGenres()) {

            int i;
            for (i = 0; i < GENRE_STRING_ENGLISH.length; i++) {
                if (genre.equals(GENRE_STRING_ENGLISH[i])) {
                    break;
                }
            }

            if (i < GENRE_STRING_ENGLISH.length) {
                if (bidiSupport) { // flip genres to for visual Hebrew display
                    genresHeb.add(GENRE_STRING_HEBREW[i]);
                } else {
                    genresHeb.add(logicalToVisual(GENRE_STRING_HEBREW[i]));
                }
            } else {
                if (bidiSupport) {
                    genresHeb.add("אחר");
                } else {
                    genresHeb.add("רחא");
                }
            }
        }

        // Set translated IMDB genres
        movie.setGenres(genresHeb, SRATIM_PLUGIN_ID);
    }

    // Return the type of a specific character
    private static int getCharType(char charToCheck) {
        if (((charToCheck >= 'א') && (charToCheck <= 'ת'))) {
            return BCT_R;
        }

        if ((charToCheck == 0x26) || (charToCheck == 0x40) || ((charToCheck >= 0x41) && (charToCheck <= 0x5A))
                || ((charToCheck >= 0x61) && (charToCheck <= 0x7A)) || ((charToCheck >= 0xC0) && (charToCheck <= 0xD6))
                || ((charToCheck >= 0xD8) && (charToCheck <= 0xDF))) {
            return BCT_L;
        }

        if (((charToCheck >= 0x30) && (charToCheck <= 0x39))) {
            return BCT_EN;
        }

        if ((charToCheck == 0x2E) || (charToCheck == 0x2F)) {
            return BCT_ES;
        }

        if ((charToCheck == 0x23) || (charToCheck == 0x24) || ((charToCheck >= 0xA2) && (charToCheck <= 0xA5)) || (charToCheck == 0x25)
                || (charToCheck == 0x2B) || (charToCheck == 0x2D) || (charToCheck == 0xB0) || (charToCheck == 0xB1)) {
            return BCT_ET;
        }

        if ((charToCheck == 0x2C) || (charToCheck == 0x3A)) {
            return BCT_CS;
        }

        // Default Natural
        return BCT_N;
    }

    // Rotate a specific part of a string
    private static void rotateString(char[] stringToRotate, int startPos, int endPos) {
        int currentPos;
        char tempChar;

        for (currentPos = 0; currentPos < (endPos - startPos + 1) / 2; currentPos++) {
            tempChar = stringToRotate[startPos + currentPos];

            stringToRotate[startPos + currentPos] = stringToRotate[endPos - currentPos];

            stringToRotate[endPos - currentPos] = tempChar;
        }

    }

    // Set the string char types
    private static void setStringCharType(char[] stringToSet, int[] charType) {
        int currentPos;

        currentPos = 0;

        while (currentPos < stringToSet.length) {
            charType[currentPos] = getCharType(stringToSet[currentPos]);

            // Fix "(" and ")"
            if (stringToSet[currentPos] == ')') {
                stringToSet[currentPos] = '(';
            } else if (stringToSet[currentPos] == '(') {
                stringToSet[currentPos] = ')';
            }

            currentPos++;
        }

    }

    // Resolving Weak Types
    private static void resolveWeakType(char[] stringType, int[] charType) {
        int pos = 0;

        while (pos < stringType.length) {
            // Check that we have at least 3 chars
            if (stringType.length - pos >= 3) {
                if ((charType[pos] == BCT_EN) && (charType[pos + 2] == BCT_EN) && ((charType[pos + 1] == BCT_ES) || (charType[pos + 1] == BCT_CS))) // Change
                // the char
                // type
                {
                    charType[pos + 1] = BCT_EN;
                }
            }

            if (stringType.length - pos >= 2) {
                if ((charType[pos] == BCT_EN) && (charType[pos + 1] == BCT_ET)) // Change the char type
                {
                    charType[pos + 1] = BCT_EN;
                }

                if ((charType[pos] == BCT_ET) && (charType[pos + 1] == BCT_EN)) // Change the char type
                {
                    charType[pos] = BCT_EN;
                }
            }

            // Default change all the terminators to natural
            if ((charType[pos] == BCT_ES) || (charType[pos] == BCT_ET) || (charType[pos] == BCT_CS)) {
                charType[pos] = BCT_N;
            }

            pos++;
        }

        /*
         * - European Numbers (FOR ES,ET,CS)
         *
         * EN,ES,EN -> EN,EN,EN EN,CS,EN -> EN,EN,EN
         *
         * EN,ET -> EN,EN ET,EN -> EN,EN ->>>>> ET=EN
         *
         *
         * else for ES,ET,CS (??)
         *
         * L,??,EN -> L,N,EN
         */
    }

    // Resolving Natural Types
    private static void resolveNaturalType(char[] stringToResolve, int[] charType, int defaultDirection) {
        int pos, checkPos;
        int before, after;

        pos = 0;

        while (pos < stringToResolve.length) {
            // Check if this is natural type and we need to cahnge it
            if (charType[pos] == BCT_N) {
                // Search for the type of the previous strong type
                checkPos = pos - 1;

                while (true) {
                    if (checkPos < 0) {
                        // Default language
                        before = defaultDirection;
                        break;
                    }

                    if (charType[checkPos] == BCT_R) {
                        before = BCT_R;
                        break;
                    }

                    if (charType[checkPos] == BCT_L) {
                        before = BCT_L;
                        break;
                    }

                    checkPos--;
                }

                checkPos = pos + 1;

                // Search for the type of the next strong type
                while (true) {
                    if (checkPos >= stringToResolve.length) {
                        // Default language
                        after = defaultDirection;
                        break;
                    }

                    if (charType[checkPos] == BCT_R) {
                        after = BCT_R;
                        break;
                    }

                    if (charType[checkPos] == BCT_L) {
                        after = BCT_L;
                        break;
                    }

                    checkPos++;
                }

                // Change the natural depanded on the strong type before and after
                if ((before == BCT_R) && (after == BCT_R)) {
                    charType[pos] = BCT_R;
                } else if ((before == BCT_L) && (after == BCT_L)) {
                    charType[pos] = BCT_L;
                } else {
                    charType[pos] = defaultDirection;
                }
            }

            pos++;
        }

        /*
         * R N R -> R R R L N L -> L L L
         *
         * L N R -> L e R (e=default) R N L -> R e L (e=default)
         */
    }

    // Resolving Implicit Levels
    private static void resolveImplictLevels(char[] stringToResolve, int[] charType, int[] level) {
        int pos;

        pos = 0;

        while (pos < stringToResolve.length) {
            if (charType[pos] == BCT_L) {
                level[pos] = 2;
            }

            if (charType[pos] == BCT_R) {
                level[pos] = 1;
            }

            if (charType[pos] == BCT_EN) {
                level[pos] = 2;
            }

            pos++;
        }
    }

    // Reordering Resolved Levels
    private static void reorderResolvedLevels(char[] stringToLevel, int[] level) {
        int count;
        int startPos, endPos, currentPos;

        for (count = 2; count >= 1; count--) {
            currentPos = 0;

            while (currentPos < stringToLevel.length) {
                // Check if this is the level start
                if (level[currentPos] >= count) {
                    startPos = currentPos;

                    // Search for the end
                    while ((currentPos + 1 != stringToLevel.length) && (level[currentPos + 1] >= count)) {
                        currentPos++;
                    }

                    endPos = currentPos;

                    rotateString(stringToLevel, startPos, endPos);
                }

                currentPos++;
            }
        }
    }

    // Convert logical string to visual
    private static void logicalToVisual(char[] stringToConvert, int defaultDirection) {
        int[] charType;
        int[] level;
        int len;

        len = stringToConvert.length;

        // Allocate CharType and Level arrays
        charType = new int[len];

        level = new int[len];

        // Set the string char types
        setStringCharType(stringToConvert, charType);

        // Resolving Weak Types
        resolveWeakType(stringToConvert, charType);

        // Resolving Natural Types
        resolveNaturalType(stringToConvert, charType, defaultDirection);

        // Resolving Implicit Levels
        resolveImplictLevels(stringToConvert, charType, level);

        // Reordering Resolved Levels
        reorderResolvedLevels(stringToConvert, level);
    }

    private static boolean isCharNatural(char c) {
        return (c == ' ') || (c == '-');
    }

    private static String logicalToVisual(String text) {
        if (bidiSupport) {
            return text; // resolves issue #1706. model >A110 Bidi support imlemented - no need to flip hebrew chars
        }
        char[] ret;

        ret = text.toCharArray();
        if (containsHebrew(ret)) {
            logicalToVisual(ret, BCT_R);
        }
        return (new String(ret));
    }

    private static List<String> logicalToVisual(List<String> text) {
        List<String> ret = new ArrayList<>();

        for (String text1 : text) {
            ret.add(logicalToVisual(text1));
        }

        return ret;
    }

    private static String removeTrailDot(String text) {
        int dot = text.lastIndexOf('.');

        if (dot == -1) {
            return text;
        }

        return text.substring(0, dot);
    }

    @SuppressWarnings("unused")
    private static String removeTrailBracket(String text) {
        int bracket = text.lastIndexOf(" (");

        if (bracket == -1) {
            return text;
        }

        return text.substring(0, bracket);
    }

    private static String breakLongLines(String text, int lineMaxChar, int lineMax) {
        StringBuilder ret = new StringBuilder();

        int scanPos = 0;
        int lastBreakPos = 0;
        int lineStart = 0;
        int lineCount = 0;

        while (scanPos < text.length()) {
            if (isCharNatural(text.charAt(scanPos))) {
                lastBreakPos = scanPos;
            }

            if (scanPos - lineStart > lineMaxChar) {
                // Check if no break position found
                if (lastBreakPos == 0) // Hard break on this location
                {
                    lastBreakPos = scanPos;
                }

                lineCount++;
                if (lineCount == lineMax) {
                    return (ret + "..." + logicalToVisual(text.substring(lineStart, lastBreakPos).trim()));
                }

                ret.append(logicalToVisual(text.substring(lineStart, lastBreakPos).trim()));

                lineStart = lastBreakPos;
                lastBreakPos = 0;

                ret.append(lineBreak);
            }

            scanPos++;
        }

        ret.append(logicalToVisual(text.substring(lineStart, scanPos).trim()));

        return ret.toString();
    }

    protected List<String> removeHtmlTags(List<String> source) {
        List<String> output = new ArrayList<>();

        for (String text : source) {
            output.add(HTMLTools.removeHtmlTags(text));
        }
        return output;
    }

    /**
     * Scan Sratim html page for the specified movie
     */
    private boolean updateMediaInfo(Movie movie) {
        try {

            String sratimUrl = "http://www.sratim.co.il/view.php?id=" + movie.getId(SRATIM_PLUGIN_ID);

            String xml = httpClient.request(sratimUrl);

            if (xml.contains("צפייה בפרופיל סדרה")) {
                if (!movie.getMovieType().equals(Movie.TYPE_TVSHOW)) {
                    movie.setMovieType(Movie.TYPE_TVSHOW);
                }
            }

            if (OverrideTools.checkOverwriteTitle(movie, SRATIM_PLUGIN_ID)) {
                String title = extractMovieTitle(xml);
                if (!"None".equalsIgnoreCase(title)) {
                    movie.setTitle(logicalToVisual(title), SRATIM_PLUGIN_ID);
                    movie.setTitleSort(title);
                }
            }

            // Prefer IMDB rating
            if (movie.getRating() == -1) {
                movie.addRating(SRATIM_PLUGIN_ID, StringTools.parseRating(HTMLTools.extractTag(xml, "width=\"120\" height=\"12\" title=\"", 0, " ")));
            }

            if (OverrideTools.checkOverwriteReleaseDate(movie, SRATIM_PLUGIN_ID)) {
                movie.setReleaseDate(HTMLTools.getTextAfterElem(xml, "י' בעולם:"), SRATIM_PLUGIN_ID);
            }

            if (OverrideTools.checkOverwriteRuntime(movie, SRATIM_PLUGIN_ID)) {
                movie.setRuntime(logicalToVisual(removeTrailDot(HTMLTools.getTextAfterElem(xml, "אורך זמן:"))), SRATIM_PLUGIN_ID);
            }

            if (OverrideTools.checkOverwriteCountry(movie, SRATIM_PLUGIN_ID)) {
                String scraped = logicalToVisual(HTMLTools.getTextAfterElem(xml, "מדינה:"));
                if (StringTools.isValidString(scraped)) {
                    List<String> countries = new ArrayList<>();
                    for (String country : StringTools.splitList(scraped, ",")) {
                        countries.add(country.trim());
                    }
                    movie.setCountries(countries, SRATIM_PLUGIN_ID);
                }
            }

            // only add if no genres set until now
            if (movie.getGenres().isEmpty()) {
                String genres = HTMLTools.getTextAfterElem(xml, "ז'אנרים:");
                List<String> newGenres = new ArrayList<>();
                for (String genre : genres.split(" *, *")) {
                    newGenres.add(logicalToVisual(Library.getIndexingGenre(genre)));
                }
                movie.setGenres(newGenres, SRATIM_PLUGIN_ID);
            }

            if (OverrideTools.checkOverwritePlot(movie, SRATIM_PLUGIN_ID)) {
                String tmpPlot = HTMLTools.removeHtmlTags(HTMLTools.extractTag(xml, "<meta name=\"description\" content=\"", "\""));
                //Set Hebrew plot only if it contains substantial number of characters, otherwise IMDB plot will be used.
                if ((tmpPlot.length() > 30)) {
                    movie.setPlot(breakLongLines(tmpPlot, plotLineMaxChar, plotLineMax), SRATIM_PLUGIN_ID);
                }
            }

            String director = logicalToVisual(HTMLTools.getTextAfterElem(xml, "בימוי:"));
            if (StringTools.isValidString(director)) {
                if (OverrideTools.checkOverwriteDirectors(movie, SRATIM_PLUGIN_ID)) {
                    movie.setDirector(director, SRATIM_PLUGIN_ID);
                }
                if (OverrideTools.checkOverwritePeopleDirectors(movie, SRATIM_PLUGIN_ID)) {
                    movie.setPeopleDirectors(Collections.singleton(director), SRATIM_PLUGIN_ID);
                }
            }

            boolean overrideActors = OverrideTools.checkOverwriteActors(movie, SRATIM_PLUGIN_ID);
            boolean overridePeopleActors = OverrideTools.checkOverwritePeopleActors(movie, SRATIM_PLUGIN_ID);
            if (overrideActors || overridePeopleActors) {
                List<String> actors = logicalToVisual(removeHtmlTags(HTMLTools.extractTags(xml, "שחקנים:", "</tr>", "<a href", "</a>")));
                if (overrideActors) {
                    movie.setCast(actors, SRATIM_PLUGIN_ID);
                }
                if (overridePeopleActors) {
                    movie.setPeopleCast(actors, SRATIM_PLUGIN_ID);
                }
            }

            if (movie.isTVShow()) {
                updateTVShowInfo(movie, xml);
            } else {
                // Download subtitle from the page
                downloadSubtitle(movie, movie.getFirstFile());
            }

        } catch (IOException error) {
            LOG.error("Failed retrieving information for movie : {}", movie.getId(SratimPlugin.SRATIM_PLUGIN_ID));
            LOG.error(SystemTools.getStackTrace(error));
        }
        return true;
    }

    private String updateImdbId(Movie movie) {
        String imdbId = movie.getId(IMDB_PLUGIN_ID);

        if (StringTools.isNotValidString(imdbId)) {
            imdbId = imdbInfo.getImdbId(movie.getTitle(), movie.getYear(), movie.isTVShow());
            movie.setId(IMDB_PLUGIN_ID, imdbId);
        }

        return imdbId;
    }

    @Override
    public void scanTVShowTitles(Movie movie) {
        scanTVShowTitles(movie, null);
    }

    public void scanTVShowTitles(Movie movie, String mainXML) {
        if (!movie.isTVShow() || !movie.hasNewMovieFiles()) {
            return;
        }

        String seasonXML = null;

        String newMainXML = mainXML;
        try {
            if (newMainXML == null) {
                String sratimId = movie.getId(SRATIM_PLUGIN_ID);
                newMainXML = httpClient.request("http://www.sratim.co.il/view.php?id=" + sratimId);
            }

            int season = movie.getSeason();

            int index = 0;
            int endIndex;

            String seasonUrl;
            String seasonYear;

            // Find the season URL
            while (true) {
                index = newMainXML.indexOf("<span class=\"smtext\"><a href=\"", index);
                if (index == -1) {
                    return;
                }

                index += 30;

                endIndex = newMainXML.indexOf('\"', index);
                if (endIndex == -1) {
                    return;
                }

                String scanUrl = newMainXML.substring(index, endIndex);

                index = newMainXML.indexOf("class=\"smtext\">עונה ", index);
                if (index == -1) {
                    return;
                }

                index += 20;

                endIndex = newMainXML.indexOf("</a>", index);
                if (endIndex == -1) {
                    return;
                }

                String scanSeason = newMainXML.substring(index, endIndex);

                index = newMainXML.indexOf("class=\"smtext\">", index);
                if (index == -1) {
                    return;
                }

                index += 15;

                endIndex = newMainXML.indexOf('<', index);
                if (endIndex == -1) {
                    return;
                }

                String scanYear = newMainXML.substring(index, endIndex);

                int scanSeasontInt = NumberUtils.toInt(scanSeason);

                if (scanSeasontInt == season) {
                    seasonYear = scanYear;
                    seasonUrl = "http://www.sratim.co.il/" + HTMLTools.decodeHtml(scanUrl);
                    break;
                }
            }

            if (OverrideTools.checkOverwriteYear(movie, SRATIM_PLUGIN_ID)) {
                movie.setYear(seasonYear, SRATIM_PLUGIN_ID);
            }

            seasonXML = httpClient.request(seasonUrl);

        } catch (IOException error) {
            LOG.error("Failed retreiving information for movie : {}", movie.getId(SratimPlugin.SRATIM_PLUGIN_ID));
            LOG.error(SystemTools.getStackTrace(error));

            if (newMainXML == null) {
                return;
            }
        }

        if (seasonXML == null) {
            return;
        }

        for (MovieFile file : movie.getMovieFiles()) {
            if (!file.isNewFile()) {
                // don't scan episode if file is not new
                continue;
            }

            for (int part = file.getFirstPart(); part <= file.getLastPart(); ++part) {

                int index = 0;
                int endIndex;

                // Go over the page and sacn for episode links
                while (true) {
                    index = seasonXML.indexOf("<td style=\"padding-right:6px;font-size:15px;\"><a href=\"", index);
                    if (index == -1) {
                        return;
                    }

                    index += 55;

                    endIndex = seasonXML.indexOf('\"', index);
                    if (endIndex == -1) {
                        return;
                    }

                    String scanUrl = seasonXML.substring(index, endIndex);

                    index = seasonXML.indexOf("<b>פרק ", index);
                    if (index == -1) {
                        return;
                    }

                    index += 7;

                    endIndex = seasonXML.indexOf(':', index);
                    if (endIndex == -1) {
                        return;
                    }

                    String scanPart = seasonXML.substring(index, endIndex);

                    index = seasonXML.indexOf("</b> ", index);
                    if (index == -1) {
                        return;
                    }

                    index += 5;

                    endIndex = seasonXML.indexOf("</a>", index);
                    if (endIndex == -1) {
                        return;
                    }

                    String scanName = seasonXML.substring(index, endIndex);

                    if (scanPart.equals(Integer.toString(part))) {

                        if (OverrideTools.checkOverwriteEpisodeTitle(file, part, SRATIM_PLUGIN_ID)) {
                            String episodeTitle = logicalToVisual(HTMLTools.decodeHtml(scanName));
                            file.setTitle(part, episodeTitle, SRATIM_PLUGIN_ID);
                        }

                        try {
                            String episodeUrl = "http://www.sratim.co.il/" + HTMLTools.decodeHtml(scanUrl);

                            // Get the episode page url
                            String xml = httpClient.request(episodeUrl);

                            // Update Plot
                            if (OverrideTools.checkOverwriteEpisodePlot(file, part, SRATIM_PLUGIN_ID)) {
                                String plot = HTMLTools.extractTag(xml, "<div style=\"font-size:14px;text-align:justify;\">", "</div>");
                                plot = HTMLTools.removeHtmlTags(plot);
                                file.setPlot(part, breakLongLines(plot, plotLineMaxChar, plotLineMax), SRATIM_PLUGIN_ID);
                            }

                            // Download subtitles
                            // store the subtitles id in the movie ids map, make sure to remove the prefix "1" from the id
                            int findId = scanUrl.indexOf("id=");
                            String subId = scanUrl.substring(findId + 4);
                            movie.setId(SRATIM_PLUGIN_SUBTITLE_ID, subId);
                            downloadSubtitle(movie, file);

                        } catch (IOException error) {
                            LOG.error("Error - {}", error.getMessage());
                        }

                        break;
                    }

                }
            }
        }
    }

    protected void updateTVShowInfo(Movie movie, String mainXML) {
        scanTVShowTitles(movie, mainXML);
    }

    public void downloadSubtitle(Movie movie, MovieFile mf) throws IOException {

        if (!subtitleDownload) {
            mf.setSubtitlesExchange(true);
            return;
        }

        // Get the file base name
        String path = mf.getFile().getName().toUpperCase();
        int lindex = path.lastIndexOf('.');
        if (lindex == -1) {
            return;
        }

        String basename = path.substring(0, lindex);

        // Check if this is a bluray file
        boolean bluRay = false;
        if (path.endsWith(".M2TS") && path.startsWith("0")) {
            bluRay = true;
        }

        if (movie.isExtra()) {
            mf.setSubtitlesExchange(true);
            return;
        }

        // Check if this movie already have subtitles for it (.srt and .sub)
        if (hasExistingSubtitles(mf, bluRay)) {
            mf.setSubtitlesExchange(true);
            return;
        }

        basename = basename.replace('.', ' ').replace('-', ' ').replace('_', ' ');

        LOG.debug("Download Subtitle: {}", mf.getFile().getAbsolutePath());
        LOG.debug("Basename         : {}", basename);
        LOG.debug("BluRay           : {}", bluRay);

        int bestFPSCount = 0;
        int bestBlurayCount = 0;
        int bestBlurayFPSCount = 0;

        String bestFPSID = "";
        String bestBlurayID = "";
        String bestBlurayFPSID = "";
        String bestFileID = "";
        String bestSimilar = "";

        // retrieve subtitles page
        String subID = movie.getId(SRATIM_PLUGIN_SUBTITLE_ID);
        String mainXML = httpClient.request("http://www.sratim.co.il/subtitles.php?mid=" + subID);

        int index = 0;
        int endIndex;

        // find the end of hebrew subtitles section, to prevent downloading non-hebrew ones
        int endHebrewSubsIndex = findEndOfHebrewSubtitlesSection(mainXML);

        // Check that hebrew subtitle exist
        String hebrewSub = HTMLTools.getTextAfterElem(mainXML, "<img src=\"images/Flags/1.png");

        LOG.debug("hebrewSub: {}", hebrewSub);

        // Check that there is no 0 hebrew sub
        if (Movie.UNKNOWN.equals(hebrewSub)) {
            LOG.debug("No Hebrew subtitles");
            return;
        }

        double maxMatch = 0.0;
        double matchThreshold = PropertiesUtil.getFloatProperty("sratim.textMatchSimilarity", 0.8f);

        while (index < endHebrewSubsIndex) {

            //
            // scanID
            //
            index = mainXML.indexOf("href=\"downloadsubtitle.php?id=", index);
            if (index == -1) {
                break;
            }

            index += 30;

            endIndex = mainXML.indexOf('\"', index);
            if (endIndex == -1) {
                break;
            }

            String scanID = mainXML.substring(index, endIndex);

            //
            // scanDiscs
            //
            index = mainXML.indexOf("src=\"images/cds/cd", index);
            if (index == -1) {
                break;
            }

            index += 18;

            endIndex = mainXML.indexOf('.', index);
            if (endIndex == -1) {
                break;
            }

            String scanDiscs = mainXML.substring(index, endIndex);

            //
            // scanFileName
            //
            index = mainXML.indexOf("subtitle_title\" style=\"direction:ltr;\" title=\"", index);
            if (index == -1) {
                break;
            }

            index += 46;

            endIndex = mainXML.indexOf('\"', index);
            if (endIndex == -1) {
                break;
            }

            String scanFileName = mainXML.substring(index, endIndex).toUpperCase().replace('.', ' ');
            // removing all characters causing metric to hang.
            scanFileName = scanFileName.replaceAll("-|\u00A0", " ").replaceAll(" ++", " ");

            //
            // scanFormat
            //
            index = mainXML.indexOf("\u05e4\u05d5\u05e8\u05de\u05d8", index); // the hebrew letters for the word "format"
            if (index == -1) {
                break;
            }

            index += 6;

            endIndex = mainXML.indexOf(',', index);
            if (endIndex == -1) {
                break;
            }

            String scanFormat = mainXML.substring(index, endIndex);

            //
            // scanFPS
            //
            index = mainXML.indexOf("\u05dc\u05e9\u05e0\u0027\u003a", index); // the hebrew letters for the word "for sec':" lamed shin nun ' :
            if (index == -1) {
                break;
            }

            index += 5;

            endIndex = mainXML.indexOf('<', index);
            if (endIndex == -1) {
                break;
            }

            String scanFPS = mainXML.substring(index, endIndex);

            //
            // scanCount
            //
            index = mainXML.indexOf("subt_date\"><span class=\"smGray\">", index);
            if (index == -1) {
                break;
            }

            index += 32;

            endIndex = mainXML.indexOf(' ', index);
            if (endIndex == -1) {
                break;
            }

            String scanCount = mainXML.substring(index, endIndex);

            // Check for best text similarity
            double result = StringUtils.getJaroWinklerDistance(basename, scanFileName);
            if (result > maxMatch) {
                maxMatch = result;
                bestSimilar = scanID;
            }

            LOG.debug("scanFileName: {} scanFPS: {} scanID: {} scanCount: {} scanDiscs: {} scanFormat: {} similarity: {}", scanFileName, scanFPS, scanID, scanCount, scanDiscs, scanFormat, result);

            // Check if movie parts matches
            int nDiscs = movie.getMovieFiles().size();
            if (!String.valueOf(nDiscs).equals(scanDiscs)) {
                continue;
            }

            // Check for exact file name
            if (scanFileName.equals(basename)) {
                bestFileID = scanID;
                break;
            }

            int scanCountInt = NumberUtils.toInt(scanCount, 0);
            float scanFPSFloat = NumberUtils.toFloat(scanFPS, 0F);

            LOG.debug("FPS: {} scanFPS: {}", movie.getFps(), scanFPSFloat);

            if (bluRay
                    && ((scanFileName.contains("BRRIP")) || (scanFileName.contains("BDRIP")) || (scanFileName.contains("BLURAY"))
                    || (scanFileName.contains("BLU-RAY")) || (scanFileName.contains("HDDVD")))) {

                if ((Float.compare(scanFPSFloat, 0F) == 0) && (scanCountInt > bestBlurayCount)) {
                    bestBlurayCount = scanCountInt;
                    bestBlurayID = scanID;
                }

                if ((Float.compare(movie.getFps(), scanFPSFloat) == 0) && (scanCountInt > bestBlurayFPSCount)) {
                    bestBlurayFPSCount = scanCountInt;
                    bestBlurayFPSID = scanID;
                }

            }

            if ((Float.compare(movie.getFps(), scanFPSFloat) == 0) && (scanCountInt > bestFPSCount)) {
                bestFPSCount = scanCountInt;
                bestFPSID = scanID;
            }

        }

        // Select the best subtitles ID
        String bestID;

        // Check for exact file name match
        if(StringUtils.isNotBlank(bestFileID)){
            LOG.debug("Best Filename");
            bestID = bestFileID;
        } else if (maxMatch >= matchThreshold) {
            // Check for text similarity match, similarity threshold takes precedence over FPS check
            LOG.debug("Best Text Similarity threshold");
            bestID = bestSimilar;
        } else if (StringUtils.isNotBlank(bestBlurayFPSID)) {
            // Check for bluray match
            LOG.debug("Best Bluray FPS");
            bestID = bestBlurayFPSID;
        } else if (StringUtils.isNotBlank(bestBlurayID)) {
            // Check for bluray match
            LOG.debug("Best Bluray");
            bestID = bestBlurayID;
        } else if (StringUtils.isNotBlank(bestFPSID)) {
            // Check for fps match
            LOG.debug("Best FPS");
            bestID = bestFPSID;
        } else if (maxMatch > 0) {
            // Check for text match, now just choose the best similar name
            LOG.debug("Best Similar");
            bestID = bestSimilar;
        } else {
            LOG.debug("No subtitle found");
            return;
        }

        LOG.debug("bestID: {}", bestID);

        // reconstruct movie filename with full path
        String orgName = mf.getFile().getAbsolutePath();
        File subtitleFile = new File(orgName.substring(0, orgName.lastIndexOf('.')));
        if (!downloadSubtitleZip(movie, "http://www.sratim.co.il/downloadsubtitle.php?id=" + bestID, subtitleFile, bluRay)) {
            LOG.error("Error - Subtitle download failed");
            return;
        }

        mf.setSubtitlesExchange(true);
        SubtitleTools.addMovieSubtitle(movie, "YES");
    }

    public boolean downloadSubtitleZip(Movie movie, String subDownloadLink, File subtitleFile, boolean bluray) {

        @SuppressWarnings("resource")
        OutputStream fileOutputStream = null;
        HttpURLConnection connection = null;
        boolean found = false;

        try {
            URL url = new URL(subDownloadLink);
            connection = (HttpURLConnection) (url.openConnection(YamjHttpClientBuilder.getProxy()));
            String contentType = connection.getContentType();
            LOG.debug("contentType: {}", contentType);

            // Check that the content is zip and that the site did not blocked the download
            if (!"application/octet-stream".equals(contentType)) {
                LOG.error("********** Error - Sratim subtitle download limit may have been reached. Suspending subtitle download.");

                subtitleDownload = false;
                return false;
            }

            Collection<MovieFile> parts = movie.getMovieFiles();
            Iterator<MovieFile> partsIter = parts.iterator();

            byte[] buf = new byte[1024];
            try (InputStream inputStream = connection.getInputStream();
                ZipInputStream zipInputStream = new ZipInputStream(inputStream))
            {
    
                ZipEntry zipentry = zipInputStream.getNextEntry();
                while (zipentry != null) {
                    // for each entry to be extracted
                    String entryName = zipentry.getName();
    
                    LOG.debug("ZIP entryname: {}", entryName);
    
                    // Check if this is a subtitle file
                    if (entryName.toUpperCase().endsWith(".SRT") || entryName.toUpperCase().endsWith(".SUB")) {
    
                        int n;
    
                        String entryExt = entryName.substring(entryName.lastIndexOf('.'));
    
                        if (movie.isTVShow()) {
                            // for tv show, use the subtitleFile parameter because tv show is
                            // handled by downloading subtitle from the episode page (each episode for its own)
                            fileOutputStream = FileTools.createFileOutputStream(subtitleFile + entryExt);
                        } else {
                            // for movie, we need to save all subtitles entries
                            // from inside the zip file, and name them according to
                            // the movie file parts.
                            if (partsIter.hasNext()) {
                                MovieFile moviePart = partsIter.next();
                                String partName = moviePart.getFile().getAbsolutePath();
                                if (bluray) { // This is a BDRip, should be saved as index.EXT under BDMV dir to match PCH requirments
                                    partName = partName.substring(0, partName.lastIndexOf("BDMV")) + "BDMV\\index";
                                } else {
                                    partName = partName.substring(0, partName.lastIndexOf('.'));
                                }
                                fileOutputStream = FileTools.createFileOutputStream(partName + entryExt);
                            } else {
                                // in case of some mismatch, use the old code
                                fileOutputStream = FileTools.createFileOutputStream(subtitleFile + entryExt);
                            }
                        }
    
                        while ((n = zipInputStream.read(buf, 0, 1024)) > -1) {
                            fileOutputStream.write(buf, 0, n);
                        }
    
                        found = true;
                    }
    
                    zipInputStream.closeEntry();
                    zipentry = zipInputStream.getNextEntry();
                }
            }
        } catch (IOException error) {
            LOG.error("Error - {}", error.getMessage());
            return false;
        } finally {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            } catch (IOException e) {
                // Ignore
            }

            if (connection != null) {
                connection.disconnect();
            }

        }

        return found;
    }

    @Override
    public boolean scanNFO(String nfo, Movie movie) {
        boolean found = super.scanNFO(nfo, movie);
        if (found) {
            return true; // IMDB nfo found, no need of further scanning.
        }
        LOG.debug("Scanning NFO for sratim url");
        Matcher m = NFO_PATTERN.matcher(nfo);
        while (m.find()) {
            String url = m.group();
            if (!url.endsWith(".jpg") && !url.endsWith(".jpeg") && !url.endsWith(".gif") && !url.endsWith(".png") && !url.endsWith(".bmp")) {
                found = true;
                movie.setId(SRATIM_PLUGIN_ID, url);
            }
        }
        if (found) {
            LOG.debug("URL found in nfo = {}", movie.getId(SRATIM_PLUGIN_ID));
        } else {
            LOG.debug("No URL found in nfo!");
        }
        return found;
    }

    private static int findEndOfHebrewSubtitlesSection(String mainXML) {
        int result = mainXML.length();
        boolean onlyHeb = PropertiesUtil.getBooleanProperty("sratim.downloadOnlyHebrew", Boolean.FALSE);
        if (onlyHeb) {
            String pattern = "images/Flags/2.png";
            int nonHeb = mainXML.indexOf(pattern);
            if (nonHeb == -1) {
                result = mainXML.length();
            }
        }
        return result;
    }

    protected String extractMovieTitle(String xml) {
        String tmpTitle = HTMLTools.removeHtmlTags(HTMLTools.extractTag(xml, "<title>", "</title>"));
        int index = tmpTitle.indexOf('(');
        if (index == -1) {
            return "None";
        }

        return tmpTitle.substring(0, index);
    }

    protected boolean hasExistingSubtitles(MovieFile mf, boolean bluray) {
        if (bluray) { // Check if the BDRIp folder contains subtitle file in PCH expected convention.
            int bdFolderIndex = mf.getFile().getAbsolutePath().lastIndexOf("BDMV");
            if (bdFolderIndex == -1) {
                LOG.debug("Could not find BDMV FOLDER, Invalid BDRip Stracture, subtitle wont be downloaded");
                return true;
            }
            String bdFolder = mf.getFile().getAbsolutePath().substring(0, bdFolderIndex);
            // String debug = "";

            File subIndex = new File(bdFolder + "BDMV//index.sub");
            File srtIndex = new File(bdFolder + "BDMV//index.srt");
            return subIndex.exists() || srtIndex.exists();
        }

        // Check if this movie already has subtitles for it
        File subtitleFile = FileTools.findSubtitles(mf.getFile());
        return subtitleFile.exists();
    }

    protected static boolean containsHebrew(char[] chars) {
        if (chars == null) {
            return false;
        }
        for (int i = 0; i < chars.length; i++) {
            if (getCharType(chars[i]) == BCT_R) {
                return true;
            }
        }
        return false;
    }
}
