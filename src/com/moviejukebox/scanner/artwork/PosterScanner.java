/*
 *      CfoundLopyright (c) 2004-2010 YAMJ Members
 *      http://code.google.com/p/moviejukebox/people/list 
 *  
 *      Web: http://code.google.com/p/moviejukebox/
 *  
 *      This software is licensed under a Creative Commons License
 *      See this page: http://code.google.com/p/moviejukebox/wiki/License
 *  
 *      For any reuse or distribution, you must make clear to others the 
 *      license terms of this work.  
 */

/**
 * Scanner for posters.
 * Includes local searches (scan) and Internet Searches
 */
package com.moviejukebox.scanner.artwork;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.moviejukebox.model.Movie;
import com.moviejukebox.model.IImage;
import com.moviejukebox.model.Image;
import com.moviejukebox.plugin.poster.IMoviePosterPlugin;
import com.moviejukebox.plugin.poster.IPosterPlugin;
import com.moviejukebox.plugin.poster.ITvShowPosterPlugin;
import com.moviejukebox.tools.FileTools;
import com.moviejukebox.tools.PropertiesUtil;
import com.moviejukebox.tools.WebBrowser;

/**
 * Scanner for poster files in local directory and from the Internet
 * 
 * @author groll.troll
 * @author Stuart.Boston
 * 
 * @version 1.0, 7 October 2008
 * @version 2.0 6 July 2009
 */
public class PosterScanner {
    private static Map<String, IPosterPlugin> posterPlugins;
    private static Map<String, IMoviePosterPlugin> moviePosterPlugins = new HashMap<String, IMoviePosterPlugin>();
    private static Map<String, ITvShowPosterPlugin> tvShowPosterPlugins = new HashMap<String, ITvShowPosterPlugin>();

    protected static Logger logger = Logger.getLogger("moviejukebox");
    protected static String[] posterExtensions;
    protected static String searchForExistingPoster;
    protected static String fixedCoverArtName;
    protected static String coverArtDirectory;
    protected static Boolean useFolderImage;
    protected static Collection<String> posterImageName;
    protected static WebBrowser webBrowser;
    protected static String preferredPosterSearchEngine;
    protected static String posterSearchPriority;
    protected static boolean posterValidate;
    protected static int posterValidateMatch;
    protected static boolean posterValidateAspect;
    protected static int posterWidth;
    protected static int posterHeight;
    private static String tvShowPosterSearchPriority;
    private static String moviePosterSearchPriority;

    static {
    	StringTokenizer st;
    	
        // We get covert art scanner behaviour
        searchForExistingPoster = PropertiesUtil.getProperty("poster.scanner.searchForExistingPoster", "moviename");
        // We get the fixed name property
        fixedCoverArtName = PropertiesUtil.getProperty("poster.scanner.fixedCoverArtName", "folder");
        // See if we use folder.* image or not
        // Note: We need the useFolderImage because of the special "folder.jpg" case in windows.
        useFolderImage = Boolean.parseBoolean(PropertiesUtil.getProperty("poster.scanner.useFolderImage", "false"));

        if (useFolderImage) {
	        st = new StringTokenizer(PropertiesUtil.getProperty("poster.scanner.imageName", "folder,poster"), ",;|");
	        posterImageName = new ArrayList<String>();
	        while (st.hasMoreTokens()) {
	        	posterImageName.add(st.nextToken());
	        }
        }
        
        // We get valid extensions
        st = new StringTokenizer(PropertiesUtil.getProperty("poster.scanner.coverArtExtensions", "jpg,png,gif"), ",;| ");
        Collection<String> extensions = new ArrayList<String>();
        while (st.hasMoreTokens()) {
            extensions.add(st.nextToken());
        }
        posterExtensions = extensions.toArray(new String[] {});

        // We get coverart Directory if needed
        coverArtDirectory = PropertiesUtil.getProperty("poster.scanner.coverArtDirectory", "");

        webBrowser = new WebBrowser();
        preferredPosterSearchEngine = PropertiesUtil.getProperty("imdb.alternate.poster.search", "google");
        posterWidth = Integer.parseInt(PropertiesUtil.getProperty("posters.width", "0"));
        posterHeight = Integer.parseInt(PropertiesUtil.getProperty("posters.height", "0"));
        tvShowPosterSearchPriority = PropertiesUtil.getProperty("poster.scanner.SearchPriority.tv", "thetvdb,cdon,filmaffinity");
        moviePosterSearchPriority = PropertiesUtil.getProperty("poster.scanner.SearchPriority.movie",
                        "moviedb,impawards,imdb,moviecovers,google,yahoo,motechnet");
        posterValidate = Boolean.parseBoolean(PropertiesUtil.getProperty("poster.scanner.Validate", "true"));
        posterValidateMatch = Integer.parseInt(PropertiesUtil.getProperty("poster.scanner.ValidateMatch", "75"));
        posterValidateAspect = Boolean.parseBoolean(PropertiesUtil.getProperty("poster.scanner.ValidateAspect", "true"));

        // Load plugins
        posterPlugins = new HashMap<String, IPosterPlugin>();

        ServiceLoader<IMoviePosterPlugin> moviePosterPluginsSet = ServiceLoader.load(IMoviePosterPlugin.class);
        for (IMoviePosterPlugin iPosterPlugin : moviePosterPluginsSet) {
            register(iPosterPlugin.getName().toLowerCase().trim(), iPosterPlugin);
        }

        ServiceLoader<ITvShowPosterPlugin> tvShowPosterPluginsSet = ServiceLoader.load(ITvShowPosterPlugin.class);
        for (ITvShowPosterPlugin iPosterPlugin : tvShowPosterPluginsSet) {
            register(iPosterPlugin.getName().toLowerCase().trim(), iPosterPlugin);
        }

    }

    public static boolean scan(String jukeboxDetailsRoot, String tempJukeboxDetailsRoot, Movie movie) {
        if (searchForExistingPoster.equalsIgnoreCase("no")) {
            // nothing to do we return
            return false;
        }

        String localPosterBaseFilename = Movie.UNKNOWN;
        String parentPath = FileTools.getParentFolder(movie.getFile());
        String fullPosterFilename = parentPath;
        File localPosterFile = null;

        if (searchForExistingPoster.equalsIgnoreCase("moviename")) {
            // Encode the basename to ensure that non-usable file system characters are replaced
            // Issue 1155 : YAMJ refuses to pickup fanart and poster for a movie -
            // Use the encoded filename first. We'll check the unencoded filename later
            localPosterBaseFilename = movie.getBaseName();
        } else if (searchForExistingPoster.equalsIgnoreCase("fixedcoverartname")) {
            localPosterBaseFilename = fixedCoverArtName;
        } else {
            logger.fine("PosterScanner: Wrong value for poster.scanner.searchForExistingPoster properties!");
            logger.fine("PosterScanner: Expected 'moviename' or 'fixedcoverartname'");
            return false;
        }

        if (!coverArtDirectory.equals("")) {
            fullPosterFilename += File.separator + coverArtDirectory;
        }
        
        // Check to see if the fullPosterFilename ends with a "\/" and only add it if needed
        // Usually this occurs because the files are at the root of a folder
        fullPosterFilename += (fullPosterFilename.endsWith(File.separator)?"":File.separator) + localPosterBaseFilename;
        localPosterFile = FileTools.findFileFromExtensions(fullPosterFilename, posterExtensions);
        boolean foundLocalCoverArt = localPosterFile.exists();

        // If we can't find the poster using the encoded filename, check for the un-encoded filename
        if (!foundLocalCoverArt && searchForExistingPoster.equalsIgnoreCase("moviename")) {
            if (!movie.getBaseName().equals(movie.getBaseFilename())) {
                fullPosterFilename += (fullPosterFilename.endsWith(File.separator)?"":File.separator) + movie.getBaseFilename();
                localPosterFile = FileTools.findFileFromExtensions(fullPosterFilename, posterExtensions);
                foundLocalCoverArt = localPosterFile.exists();
            }
        }

        
        /**
         * This part will look for a filename with the same name as the directory for the cover art or for folder.* coverart The intention is for you to be able
         * to create the season / TV series art for the whole series and not for the first show. Useful if you change the files regularly.
         * 
         * @author Stuart.Boston
         * @version 1.0
         * @date 18th October 2008
         */
        if (!foundLocalCoverArt) {
            // if no coverart has been found, try the foldername
            // no need to check the coverart directory
            localPosterBaseFilename = FileTools.getParentFolderName(movie.getFile());

            if (useFolderImage) {
                // Checking for MovieFolderName.* AND folder.*
                logger.finest("PosterScanner: Checking for '" + localPosterBaseFilename + ".*' coverart AND " + posterImageName + ".* coverart");
            } else {
                // Only checking for the MovieFolderName.* and not folder.*
                logger.finest("PosterScanner: Checking for '" + localPosterBaseFilename + ".*' coverart");
            }

            // Check for the directory name with extension for coverart
            fullPosterFilename = parentPath + File.separator + localPosterBaseFilename;
            localPosterFile = FileTools.findFileFromExtensions(fullPosterFilename, posterExtensions);
            foundLocalCoverArt = localPosterFile.exists();
            if(!foundLocalCoverArt && useFolderImage){
            	for (String imageFileName : posterImageName) {
	                // logger.finest("Checking for '" + imageFileName + ".*' coverart");
	                fullPosterFilename = FileTools.getParentFolder(movie.getFile()) + File.separator + imageFileName;
	                localPosterFile = FileTools.findFileFromExtensions(fullPosterFilename, posterExtensions);
	                foundLocalCoverArt = localPosterFile.exists();
	                
	                if (!foundLocalCoverArt && movie.isTVShow()) {
	                    // Get the parent directory and check that
		                fullPosterFilename = FileTools.getParentFolder(movie.getFile().getParentFile().getParentFile()) + File.separator + imageFileName;
		                System.out.println("SCANNER: " + fullPosterFilename);
		                localPosterFile = FileTools.findFileFromExtensions(fullPosterFilename, posterExtensions);
		                foundLocalCoverArt = localPosterFile.exists();
		                if (foundLocalCoverArt) {
		                	break;   // We found the artwork so quit the loop
		                }
	                } else {
	                	break;    // We found the artwork so quit the loop
	                }
            	}
            }
        }
        /*
         * END OF Folder CoverArt
         */

        if (foundLocalCoverArt) {
            fullPosterFilename = localPosterFile.getAbsolutePath();
            logger.finest("PosterScanner: Poster file " + fullPosterFilename + " found");

            String safePosterFilename = movie.getPosterFilename();
            String finalDestinationFileName = jukeboxDetailsRoot + File.separator + safePosterFilename;
            String destFileName = tempJukeboxDetailsRoot + File.separator + safePosterFilename;

            File finalDestinationFile = FileTools.fileCache.getFile(finalDestinationFileName);
            File destFile = new File(destFileName);
            boolean checkAgain = false;

            // Overwrite the jukebox files if the local file is newer
            // First check the temp jukebox file
            if (localPosterFile.exists() && destFile.exists()) {
                if (FileTools.isNewer(localPosterFile, destFile)) {
                    checkAgain = true;
                }
            } else if (localPosterFile.exists() && finalDestinationFile.exists()) {
                // Check the target jukebox file
                if (FileTools.isNewer(localPosterFile, finalDestinationFile)) {
                    checkAgain = true;
                }
            }

            if ((localPosterFile.length() != finalDestinationFile.length()) || (FileTools.isNewer(localPosterFile, finalDestinationFile))) {
                // Poster size is different OR Local Poster is newer
                checkAgain = true;
            }

            if (!finalDestinationFile.exists() || checkAgain) {
                FileTools.copyFile(localPosterFile, destFile);
                logger.finer("PosterScanner: " + fullPosterFilename + " has been copied to " + destFileName);
            } else {
                logger.finer("PosterScanner: " + finalDestinationFileName + " is different to " + fullPosterFilename);
            }

            // Update poster url with local poster
            movie.setPosterURL(localPosterFile.toURI().toString());
            return true;
        } else {
            logger.finer("PosterScanner: No local covertArt found for " + movie.getBaseName());
            return false;
        }
    }

    private static String getPluginsCode() {
        String response = "";

        Set<String> keySet = posterPlugins.keySet();
        for (String string : keySet) {
            response += string + " / ";
        }
        return response;
    }

    /**
     * Locate the PosterURL from the Internet. This is the main method and should be called instead of the individual getPosterFrom* methods.
     * 
     * @param movie
     *            The movieBean to search for
     * @return The posterImage with poster url that was found (Maybe Image.UNKNOWN)
     */
    public static IImage getPosterURL(Movie movie) {
        String posterSearchToken;
        IImage posterImage = Image.UNKNOWN;
        StringTokenizer st;

        if (movie.isTVShow()) {
            st = new StringTokenizer(tvShowPosterSearchPriority, ",");
        } else {
            st = new StringTokenizer(moviePosterSearchPriority, ",");
        }

        while (st.hasMoreTokens() && posterImage.getUrl().equalsIgnoreCase(Movie.UNKNOWN)) {
            posterSearchToken = st.nextToken();

            IPosterPlugin iPosterPlugin = posterPlugins.get(posterSearchToken);
            // Check that plugin is register even on movie or tv
            if (iPosterPlugin == null) {
                logger.severe("Posterscanner: '" + posterSearchToken + "' plugin doesn't exist, please check you moviejukebox properties. Valid plugins are : "
                                + getPluginsCode());
            }
            String msg = null;
            if (movie.isTVShow()) {
                iPosterPlugin = tvShowPosterPlugins.get(posterSearchToken);
                msg = "TvShow";
            } else {
                iPosterPlugin = moviePosterPlugins.get(posterSearchToken);
                msg = "Movie";
            }
            
            if (iPosterPlugin == null) {
                logger.info("Posterscanner: " + posterSearchToken + " is not a " + msg + " Poster plugin - skipping");
            } else {
                posterImage = iPosterPlugin.getPosterUrl(movie, movie);
            }

            // Validate the poster- No need to validate if we're UNKNOWN
            if (!Movie.UNKNOWN.equalsIgnoreCase(posterImage.getUrl()) && posterValidate && !validatePoster(posterImage, posterWidth, posterHeight, posterValidateAspect)) {
                posterImage = Image.UNKNOWN;
            } else {
                if (!Movie.UNKNOWN.equalsIgnoreCase(posterImage.getUrl())) {
                    logger.finest("PosterScanner: Poster URL found at " + posterSearchToken + ": " + posterImage);
                }
            }
        }

        return posterImage;
    }

    public static boolean validatePoster(IImage posterImage) {
        return validatePoster(posterImage, posterWidth, posterHeight, posterValidateAspect);
    }

    /**
     * Get the size of the file at the end of the URL Taken from: http://forums.sun.com/thread.jspa?threadID=528155&messageID=2537096
     * 
     * @param posterImage
     *            Poster image to check
     * @param posterWidth
     *            The width to check
     * @param posterHeight
     *            The height to check
     * @param checkAspect
     *            Should the aspect ratio be checked
     * @return True if the poster is good, false otherwise
     */
    @SuppressWarnings("unchecked")
    public static boolean validatePoster(IImage posterImage, int posterWidth, int posterHeight, boolean checkAspect) {
        Iterator readers = ImageIO.getImageReadersBySuffix("jpeg");
        ImageReader reader = (ImageReader)readers.next();
        int urlWidth = 0, urlHeight = 0;
        float urlAspect;

        if (!posterValidate) {
            return true;
        }

        if (posterImage.getUrl().equalsIgnoreCase(Movie.UNKNOWN)) {
            return false;
        }

        try {
            URL url = new URL(posterImage.getUrl());
            InputStream in = url.openStream();
            ImageInputStream iis = ImageIO.createImageInputStream(in);
            reader.setInput(iis, true);
            urlWidth = reader.getWidth(0);
            urlHeight = reader.getHeight(0);
        } catch (IOException ignore) {
            logger.finest("PosterScanner: ValidatePoster error: " + ignore.getMessage() + ": can't open url");
            return false; // Quit and return a false poster
        }

        // Check if we need to cut the poster into a sub image
        if (!posterImage.getSubimage().equalsIgnoreCase(Movie.UNKNOWN)) {
            StringTokenizer st = new StringTokenizer(posterImage.getSubimage(), ", ");
            int x = Integer.parseInt(st.nextToken());
            int y = Integer.parseInt(st.nextToken());
            int l = Integer.parseInt(st.nextToken());
            int h = Integer.parseInt(st.nextToken());

            urlWidth = urlWidth * l / 100 - urlWidth * x / 100;
            urlHeight = urlHeight * h / 100 - urlHeight * y / 100;
        }

        urlAspect = (float)urlWidth / (float)urlHeight;

        if (checkAspect && urlAspect > 1.0) {
            logger.finest(posterImage + " rejected: URL is landscape format");
            return false;
        }

        // Adjust poster width / height by the ValidateMatch figure
        posterWidth = posterWidth * (posterValidateMatch / 100);
        posterHeight = posterHeight * (posterValidateMatch / 100);

        if (urlWidth < posterWidth) {
            logger.finest("PosterScanner: " + posterImage + " rejected: URL width (" + urlWidth + ") is smaller than poster width (" + posterWidth + ")");
            return false;
        }

        if (urlHeight < posterHeight) {
            logger.finest("PosterScanner: " + posterImage + " rejected: URL height (" + urlHeight + ") is smaller than poster height (" + posterHeight + ")");
            return false;
        }
        return true;
    }

    public static void register(String key, IPosterPlugin posterPlugin) {
        posterPlugins.put(key, posterPlugin);
    }

    private static void register(String key, IMoviePosterPlugin posterPlugin) {
        logger.finest("Posterscanner: " + posterPlugin.getClass().getName() + " register as Movie Poster Plugin with key " + key);
        moviePosterPlugins.put(key, posterPlugin);
        register(key, (IPosterPlugin)posterPlugin);
    }

    public static void register(String key, ITvShowPosterPlugin posterPlugin) {
        logger.finest("PosterScanner: " + posterPlugin.getClass().getName() + " register as TvShow Poster Plugin with key " + key);
        tvShowPosterPlugins.put(key, posterPlugin);
        register(key, (IPosterPlugin)posterPlugin);
    }

    public static void scan(Movie movie) {
        logger.finer("PosterScanner: Searching for " + movie.getBaseName());
        IImage posterImage = getPosterURL(movie);
        if (!Movie.UNKNOWN.equals(posterImage.getUrl())) {
            movie.setPosterURL(posterImage.getUrl());
            movie.setPosterSubimage(posterImage.getSubimage());
        }
    }
}