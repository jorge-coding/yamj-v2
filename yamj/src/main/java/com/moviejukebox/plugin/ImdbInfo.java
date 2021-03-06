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

import com.moviejukebox.model.Movie;
import com.moviejukebox.tools.*;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImdbInfo {

    private static final Logger LOG = LoggerFactory.getLogger(ImdbInfo.class);
    private static final String OBJECT_MOVIE = "movie";
    private static final String OBJECT_PERSON = "person";
    private static final String CATEGORY_ALL = "all";
    private static final String CATEGORY_MOVIE = "movie";
    private static final String CATEGORY_TV = "tv";
    private static final String SEARCH_FIRST = "first";
    private static final String SEARCH_EXACT = "exact";
    private static final String PAREN_LEFT = "("; // Use for searches - was %28
    private static final String PAREN_RIGHT = ")"; // Use for searches - was %29
    private static final String UTF_8 = "UTF-8";
    private static final String REGEX_START = "<link rel=\"canonical\" href=\"";
    private static final String REGEX_TITLE = "title/(tt\\d+)/\"";
    private static final String REGEX_NAME = "name/(nm\\d+)/\"";
    
    private final String searchMatch = PropertiesUtil.getProperty("imdb.id.search.match", "regular");
    private final boolean searchVariable = PropertiesUtil.getBooleanProperty("imdb.id.search.variable", Boolean.TRUE);
    private final String preferredSearchEngine = PropertiesUtil.getProperty("imdb.id.search", "imdb");
    private final YamjHttpClient httpClient;
    private final String imdbSite;
    private final Pattern personRegex;
    private final Pattern titleRegex;
    private final Charset charset;

    public ImdbInfo() {
        this.httpClient = YamjHttpClientBuilder.getHttpClient();
       
        final String site = PropertiesUtil.getProperty("imdb.site", "www");
        if ("labs".equalsIgnoreCase(site)) {
            this.imdbSite = "http://labs.imdb.com/";
        } else if ("akas".equalsIgnoreCase(site)) {
            this.imdbSite = "http://akas.imdb.com/";
        } else {
            this.imdbSite = "http://www.imdb.com/";
        }

        this.personRegex = Pattern.compile(Pattern.quote(REGEX_START + this.imdbSite + REGEX_NAME));
        this.titleRegex = Pattern.compile(Pattern.quote(REGEX_START + this.imdbSite + REGEX_TITLE));
        this.charset = Charset.forName(UTF_8);
    }

    /**
     * Retrieve the IMDb matching the specified movie name and year. This routine is based on a IMDb request.
     *
     * @param movieName the name of the movie
     * @param year the year
     * @return the movie ID or UNKNOWN
     */
    public String getImdbId(String movieName, String year) {
        return getImdbId(movieName, year, CATEGORY_ALL);
    }

    /**
     * Retrieve the IMDb matching the specified movie name and year. This routine is based on a IMDb request.
     *
     * @param movieName the name of the movie
     * @param year the year
     * @param isTVShow flag to indicate if the searched movie is a TV show
     * @return the movie ID or UNKNOWN
     */
    public String getImdbId(String movieName, String year, boolean isTVShow) {
        return getImdbId(movieName, year, (isTVShow ? CATEGORY_TV : CATEGORY_MOVIE));
    }

    /**
     * Retrieve the IMDb matching the specified movie name and year. This routine is based on a IMDb request.
     *
     * @param movieName the name of the movie
     * @param year the year
     * @param categoryType the type of the category to search within
     * @return the movie ID or UNKNOWN
     */
    private String getImdbId(String movieName, String year, String categoryType) {
        if ("google".equalsIgnoreCase(preferredSearchEngine)) {
            return getImdbIdFromGoogle(movieName, year, OBJECT_MOVIE);
        } else if ("yahoo".equalsIgnoreCase(preferredSearchEngine)) {
            return getImdbIdFromYahoo(movieName, year, OBJECT_MOVIE);
        } else if ("none".equalsIgnoreCase(preferredSearchEngine)) {
            return Movie.UNKNOWN;
        } else {
            return getImdbIdFromImdb(movieName, year, OBJECT_MOVIE, categoryType);
        }
    }

    /**
     * Get the IMDb ID for a person. Note: The job is not used in this search.
     *
     * @param personName
     * @param movieId
     * @return
     */
    public String getImdbPersonId(String personName, String movieId) {
        try {
            if (StringTools.isValidString(movieId)) {
                StringBuilder sb = new StringBuilder(this.imdbSite);
                sb.append("search/name?name=");
                sb.append(URLEncoder.encode(personName, UTF_8)).append("&role=").append(movieId);

                LOG.debug("Querying IMDB for {}", sb.toString());
                String xml = httpClient.request(sb.toString());

                // Check if this is an exact match (we got a person page instead of a results list)
                Matcher titlematch = this.personRegex.matcher(xml);
                if (titlematch.find()) {
                    LOG.debug("IMDb returned one match {}", titlematch.group(1));
                    return titlematch.group(1);
                }

                String firstPersonId = HTMLTools.extractTag(HTMLTools.extractTag(xml, "<tr class=\"even detailed\">", "</tr>"), "<a href=\"/name/", "/\"");
                if (StringTools.isValidString(firstPersonId)) {
                    return firstPersonId;
                }
            }

            return getImdbPersonId(personName);
        } catch (IOException ex) {
            LOG.error("Failed retreiving IMDb Id for person : {}", personName);
            LOG.error(SystemTools.getStackTrace(ex));
        }

        return Movie.UNKNOWN;
    }

    /**
     * Get the IMDb ID for a person
     *
     * @param personName
     * @return
     */
    public String getImdbPersonId(String personName) {
        if ("google".equalsIgnoreCase(preferredSearchEngine)) {
            return getImdbIdFromGoogle(personName, Movie.UNKNOWN, OBJECT_PERSON);
        } else if ("yahoo".equalsIgnoreCase(preferredSearchEngine)) {
            return getImdbIdFromYahoo(personName, Movie.UNKNOWN, OBJECT_PERSON);
        } else if ("none".equalsIgnoreCase(preferredSearchEngine)) {
            return Movie.UNKNOWN;
        } else {
            return getImdbIdFromImdb(personName.toLowerCase(), Movie.UNKNOWN, OBJECT_PERSON, CATEGORY_ALL);
        }
    }

    /**
     * Retrieve the IMDb Id matching the specified movie name and year. This routine is base on a yahoo request.
     *
     * @param movieName The name of the Movie to search for
     * @param year The year of the movie
     * @return The IMDb Id if it was found
     */
    private String getImdbIdFromYahoo(String movieName, String year, String objectType) {
        try {
            StringBuilder sb = new StringBuilder("http://search.yahoo.com/search;_ylt=A1f4cfvx9C1I1qQAACVjAQx.?p=");
            sb.append(URLEncoder.encode(movieName, UTF_8));

            if (StringTools.isValidString(year)) {
                sb.append("+").append(PAREN_LEFT).append(year).append(PAREN_RIGHT);
            }

            sb.append("+site%3Aimdb.com&fr=yfp-t-501&ei=UTF-8&rd=r1");

            LOG.debug("Yahoo search: {}", sb.toString());

            return getImdbIdFromSearchEngine(sb.toString(), objectType);

        } catch (Exception ex) {
            LOG.error("Failed retreiving IMDb Id for movie : {}", movieName);
            LOG.error(SystemTools.getStackTrace(ex));
            return Movie.UNKNOWN;
        }
    }

    /**
     * Retrieve the IMDb matching the specified movie name and year. This routine is base on a Google request.
     *
     * @param movieName The name of the Movie to search for
     * @param year The year of the movie
     * @return The IMDb Id if it was found
     */
    private String getImdbIdFromGoogle(String movieName, String year, String objectType) {
        try {
            LOG.debug("querying Google for {}", movieName);

            StringBuilder sb = new StringBuilder("http://www.google.com/search?q=");
            sb.append(URLEncoder.encode(movieName, UTF_8));

            if (StringTools.isValidString(year)) {
                sb.append("+").append(PAREN_LEFT).append(year).append(PAREN_RIGHT);
            }

            sb.append("+site%3Awww.imdb.com&meta=");

            LOG.debug("Google search: {}", sb.toString());

            return getImdbIdFromSearchEngine(sb.toString(), objectType);

        } catch (Exception ex) {
            LOG.error("Failed retreiving IMDb Id for movie : {}", movieName);
            LOG.error(SystemTools.getStackTrace(ex));
            return Movie.UNKNOWN;
        }
    }

    private String getImdbIdFromSearchEngine(String requestString, String objectType) throws Exception {
        String xml = httpClient.request(requestString);
        String imdbId = Movie.UNKNOWN;

        int beginIndex = xml.indexOf(objectType.equals(OBJECT_MOVIE) ? "/title/tt" : "/name/nm");
        if (beginIndex > -1) {
            int index;
            if (objectType.equals(OBJECT_MOVIE)) {
                index = beginIndex + 7;
            } else {
                index = beginIndex + 6;
            }
            StringTokenizer st = new StringTokenizer(xml.substring(index), "/\"");
            imdbId = st.nextToken();
        }

        if (imdbId.startsWith(objectType.equals(OBJECT_MOVIE) ? "tt" : "nm")) {
            LOG.debug("Found IMDb ID: {}", imdbId);
            return imdbId;
        }
        return Movie.UNKNOWN;
    }

    /**
     * Retrieve the IMDb matching the specified movie name and year. This routine is base on a IMDb request.
     */
    private String getImdbIdFromImdb(String movieName, String year, String objectType, String categoryType) {
        /*
         * IMDb matches seem to come in several "flavours".
         *
         * Firstly, if there is one exact match it returns the matching IMDb page.
         *
         * If that fails to produce a unique hit then a list of possible matches are returned categorised as:
         *      Popular Titles (Displaying ? Results)
         *      Titles (Exact Matches) (Displaying ? Results)
         *      Titles (Partial Matches) (Displaying ? Results)
         *
         * We should check the Exact match section first, then the poplar titles and finally the partial matches.
         *
         * Note: That even with exact matches there can be more than 1 hit, for example "Star Trek"
         */

        StringBuilder sb = new StringBuilder(this.imdbSite);
        sb.append("find?q=");
        try {
            sb.append(URLEncoder.encode(movieName, UTF_8));
        } catch (UnsupportedEncodingException ex) {
            // Failed to encode the movie name for some reason!
            LOG.debug("Failed to encode movie name: {}", movieName);
            sb.append(movieName);
        }

        if (StringTools.isValidString(year)) {
            sb.append("+").append(PAREN_LEFT).append(year).append(PAREN_RIGHT);
        }
        sb.append("&s=");
        if (objectType.equals(OBJECT_MOVIE)) {
            sb.append("tt");
            if (searchVariable) {
                if (categoryType.equals(CATEGORY_MOVIE)) {
                    sb.append("&ttype=ft");
                } else if (categoryType.equals(CATEGORY_TV)) {
                    sb.append("&ttype=tv");
                }
            }
        } else {
            sb.append("nm");
        }
        sb.append("&site=aka");

        LOG.debug("Querying IMDB for {}", sb.toString());
        String xml;
        try {
            xml = httpClient.request(sb.toString());
        } catch (IOException ex) {
            LOG.error("Failed retreiving IMDb Id for movie : {}", movieName);
            LOG.error("Error : {}", ex.getMessage());
            return Movie.UNKNOWN;
        }

        // Check if this is an exact match (we got a movie page instead of a results list)
        Pattern titleRegex = this.personRegex;
        if (objectType.equals(OBJECT_MOVIE)) {
            titleRegex = this.titleRegex;
        }

        Matcher titleMatch = titleRegex.matcher(xml);
        if (titleMatch.find()) {
            LOG.debug("IMDb returned one match {}", titleMatch.group(1));
            return titleMatch.group(1);
        }

        String searchName = HTMLTools.extractTag(HTMLTools.extractTag(xml, ";ttype=ep\">", "\"</a>.</li>"), "<b>", "</b>").toLowerCase();
        final String formattedName;
        final String formattedYear;
        final String formattedExact;

        if (SEARCH_FIRST.equalsIgnoreCase(searchMatch)) {
            // first match so nothing more to check
            formattedName = null;
            formattedYear = null;
            formattedExact = null;
        } else if (StringTools.isValidString(searchName)) {
            if (StringTools.isValidString(year) && searchName.endsWith(")") && searchName.contains("(")) {
                searchName = searchName.substring(0, searchName.lastIndexOf('(') - 1);
                formattedName = searchName.toLowerCase();
                formattedYear = "(" + year + ")";
                formattedExact = formattedName + "</a> " + formattedYear;
            } else {
                formattedName = searchName.toLowerCase();
                formattedYear = "</a>";
                formattedExact = formattedName + formattedYear;
            }
        } else {
            sb = new StringBuilder();
            try {
                sb.append(URLEncoder.encode(movieName, UTF_8).replace("+", " "));
            } catch (UnsupportedEncodingException ex) {
                LOG.debug("Failed to encode movie name: {}", movieName);
                sb.append(movieName);
            }
            formattedName = sb.toString().toLowerCase();
            if (StringTools.isValidString(year)) {
                formattedYear = "(" + year + ")";
                formattedExact = formattedName + "</a> " + formattedYear;
            } else {
                formattedYear = "</a>";
                formattedExact = formattedName + formattedYear;

            }
            searchName = formattedExact;
        }

        for (String searchResult : HTMLTools.extractTags(xml, "<table class=\"findList\">", "</table>", "<td class=\"result_text\">", "</td>", false)) {
            // logger.debug("Check  : '" + searchResult + "'");
            boolean foundMatch = false;
            if (SEARCH_FIRST.equalsIgnoreCase(searchMatch)) {
                // first result matches
                foundMatch = true;
            } else if (SEARCH_EXACT.equalsIgnoreCase(searchMatch)) {
                // exact match
                foundMatch = (searchResult.toLowerCase().contains(formattedExact));
            } else {
                // regular match: name and year match independent from each other
                int nameIndex = searchResult.toLowerCase().indexOf(formattedName);
                if (nameIndex != -1) {
                    foundMatch = (searchResult.indexOf(formattedYear) > nameIndex);
                }
            }

            if (foundMatch) {
                // logger.debug("Title match  : '" + searchResult + "'");
                return HTMLTools.extractTag(searchResult, "<a href=\"" + (objectType.equals(OBJECT_MOVIE) ? "/title/" : "/name/"), "/");
            }

            for (String otherResult : HTMLTools.extractTags(searchResult, "</';\">", "</p>", "<p class=\"find-aka\">", "</em>", false)) {
                if (otherResult.toLowerCase().contains("\"" + searchName + "\"")) {
                    // logger.debug("Other title match: '" + otherResult + "'");
                    return HTMLTools.extractTag(searchResult, "/images/b.gif?link=" + (objectType.equals(OBJECT_MOVIE) ? "/title/" : "/name/"), "/';\">");
                }
            }
        }

        // alternate search for person ID
        if (objectType.equals(OBJECT_PERSON)) {
            String firstPersonId = HTMLTools.extractTag(HTMLTools.extractTag(xml, "<table><tr> <td valign=\"top\">", "</td></tr></table>"), "<a href=\"/name/", "/\"");
            if (StringTools.isNotValidString(firstPersonId)) {
                // alternate approach
                int beginIndex = xml.indexOf("<a href=\"/name/nm");
                if (beginIndex > -1) {
                    StringTokenizer st = new StringTokenizer(xml.substring(beginIndex + 15), "/\"");
                    firstPersonId = st.nextToken();
                }
            }

            if (firstPersonId.startsWith("nm")) {
                LOG.debug("Found IMDb ID: {}", firstPersonId);
                return firstPersonId;
            }
        }

        // If we don't have an ID try google
        LOG.debug("Failed to find a match on IMDb, trying Google");
        return getImdbIdFromGoogle(movieName, year, objectType);
    }

    /**
     * Get the IMDb site
     *
     * @return
     */
    public String getImdbSite() {
        return imdbSite;
    }

    /**
     * Get the charset
     *
     * @return
     */
    public Charset getCharset() {
        return charset;
    }
}
