package com.moviejukebox.plugin;

import java.io.*;
import java.net.*;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.moviejukebox.model.Library;
import com.moviejukebox.model.Movie;
import com.moviejukebox.model.MovieFile;
import com.moviejukebox.model.TrailerFile;
import com.moviejukebox.tools.HTMLTools;
import com.moviejukebox.tools.WebBrowser;
import com.moviejukebox.tools.PropertiesUtil;

public class AppleTrailersPlugin {

    private static Logger logger = Logger.getLogger("moviejukebox");

    private String configResolution;
    private String configDownload;
    
    protected WebBrowser webBrowser;

    
    public AppleTrailersPlugin() {
    
        webBrowser = new WebBrowser();

        configResolution = PropertiesUtil.getProperty("appletrailers.resolution", "");
        configDownload = PropertiesUtil.getProperty("appletrailers.download", "false");
    }


    public void generate(Movie movie) {

        // Check if trailer resoulution was selected
        if (configResolution.equals(""))
            return;

        // Check if this movie was already checked for trailers
        if (movie.isTrailerExchange())
            return;

        if (movie.isTrailer())
            return;

        if (movie.getMovieType().equals(Movie.TYPE_TVSHOW))
            return;
            

        String movieName = movie.getOriginalTitle();
        
        String trailerPageUrl = GetTrailerPageUrl(movieName);
        
        if (trailerPageUrl == Movie.UNKNOWN) {
            logger.finer("AppleTrailers Plugin: Trailer not found for " + movie.getBaseName());
            
            movie.setTrailerExchange(true);
            return;
        }
        
        
        ArrayList<String> trailersUrl = new ArrayList<String>();
        ArrayList<String> bestTrailersUrl = new ArrayList<String>();
        
        getTrailerSubUrl(trailerPageUrl, trailersUrl);
        
        selectBestTrailer(trailersUrl,bestTrailersUrl);


        for (int i=0;i<bestTrailersUrl.size();i++) {            
        
            String trailerRealUrl = bestTrailersUrl.get(i);
            
            // Add the trailer url to the movie
            MovieFile tmf = new MovieFile();
            tmf.setTitle(getTrailerTitle(trailerRealUrl));
            
            
            // Check if we need to download the trailer, or just link to it
            if (!configDownload.equals("true")) {
            
                logger.finer("AppleTrailers Plugin: Trailer found for " + movie.getBaseName() + " " + trailerRealUrl);
                
                tmf.setFilename(trailerRealUrl);
                movie.addTrailerFile(new TrailerFile(tmf));
                //movie.setTrailer(true);
            }
            else
            {
                MovieFile mf = movie.getFirstFile();
                String path = mf.getFile().getAbsolutePath();
                int index = path.lastIndexOf(".");
                String basename = path.substring(0, index + 1);
                
                int nameStart=trailerRealUrl.lastIndexOf('/')+1;
                String trailerFileName = basename + "[TRAILER]." + trailerRealUrl.substring(nameStart);
                

                String playPath = mf.getFilename();
                int playIndex = playPath.lastIndexOf(".");
                String playBasename = playPath.substring(0, playIndex + 1);
                
                String trailerPlayFileName = playBasename + "[TRAILER]." + trailerRealUrl.substring(nameStart);

                
                File trailerFile = new File(trailerFileName);
                
                // Check if the file already exist - after jukebox directory was deleted for example
                if (trailerFile.exists()) {
                
                    logger.finer("AppleTrailers Plugin: Trailer file already exist for " + movie.getBaseName());
                
                    tmf.setFilename(trailerPlayFileName);
                    movie.addTrailerFile(new TrailerFile(tmf));
                    //movie.setTrailer(true);
                }
                else {
                    if (trailerDownload(movie,trailerRealUrl,trailerFile)) {
                    
                        tmf.setFilename(trailerPlayFileName);
                        movie.addTrailerFile(new TrailerFile(tmf));
                        //movie.setTrailer(true);
                    }
                }
                
            }
        }
        
        movie.setTrailerExchange(true);
    }
    
    private String GetTrailerPageUrl(String movieName) {
        try {
        
            String searchURL = "http://www.apple.com/trailers/home/scripts/quickfind.php?callback=searchCallback&q=" + URLEncoder.encode(movieName, "iso-8859-8");

            String xml = webBrowser.request(searchURL);

            int index = 0;
            int endIndex = 0;
            while (true) {
                index = xml.indexOf("\"title\":\"", index);
                if (index == -1)
                    break;

                index += 9;

                endIndex = xml.indexOf("\",", index);
                if (endIndex == -1)
                    break;

                String trailerTitle = decodeEscapeICU(xml.substring(index, endIndex));

                index = endIndex + 2;

                index = xml.indexOf("\"location\":\"", index);
                if (index == -1)
                    break;

                index += 12;

                endIndex = xml.indexOf("\",", index);
                if (endIndex == -1)
                    break;

                String trailerLocation = decodeEscapeICU( xml.substring(index, endIndex) );

                index = endIndex + 2;
                
                
                if (trailerTitle.equalsIgnoreCase(movieName))
                {
                    String trailerUrl;
                    
                    int itmsIndex = trailerLocation.indexOf("itms://");
                    if (itmsIndex == -1) {
                        // Convert relative URL to absolute URL - some urls are already absolute, and some relative
                        trailerUrl = getAbsUrl("http://www.apple.com/trailers/" , trailerLocation);
                    }
                    else {
                        trailerUrl = "http" + trailerLocation.substring(itmsIndex+4);
                    }
                    
                    return trailerUrl;
                }
            }




        } catch (Exception e) {
            logger.severe("Failed retreiving trailer for movie : " + movieName);
            logger.severe("Error : " + e.getMessage());
            return Movie.UNKNOWN;
        }
    
        return Movie.UNKNOWN;
    }
    
    private void getTrailerSubUrl(String trailerPageUrl, ArrayList<String> trailersUrl) {
        try {
            String xml = webBrowser.request(trailerPageUrl);

            // Try to find the movie link on the main page
            getTrailerMovieUrl(xml, trailersUrl);
            

            String trailerPageUrlHD = getAbsUrl(trailerPageUrl, "hd");
            String xmlHD = webBrowser.request(trailerPageUrlHD);

            // Try to find the movie link on the HD page
            getTrailerMovieUrl(xmlHD, trailersUrl);

            // Go over the href links and check the sub pages
            
            int index = 0;
            int endIndex = 0;
            while (true) {
                index = xml.indexOf("href=\"", index);
                if (index == -1)
                    break;

                index += 6;

                endIndex = xml.indexOf("\"", index);
                if (endIndex == -1)
                    break;

                String href = xml.substring(index, endIndex);

                index = endIndex + 1;
                
                String absHref = getAbsUrl(trailerPageUrl, href);
                
                // Check if this href is a sub page of this trailer
                if (absHref.startsWith(trailerPageUrl)) {

                    String subXml = webBrowser.request(absHref);
                    
                    // Try to find the movie link on the sub page
                    getTrailerMovieUrl(subXml, trailersUrl);
                }
            }


        } catch (Exception e) {
            // Too many errors displayed due to apple missing page links
            return;
        }
    }

    private void getTrailerMovieUrl(String xml, ArrayList<String> trailersUrl) {

        Matcher m = Pattern.compile("http://(movies|images).apple.com/movies/.+?\\.(mov|m4v)").matcher(xml);
        while (m.find()) {
            String movieUrl = m.group();
            boolean duplicate = false;

            // Check for duplicate
            for (int i=0;i<trailersUrl.size();i++) {
            
                if (trailersUrl.get(i).equals(movieUrl))
                    duplicate = true;
            }
        
            if (!duplicate)
                trailersUrl.add(movieUrl);
        }
    }
     

    private void selectBestTrailer(ArrayList<String> trailersUrl,ArrayList<String> bestTrailersUrl) {
        
        if (configResolution.equals("1080p")) {
            // Search for 1080p
            for (int i=0;i<trailersUrl.size();i++) {
                
                String curURL = trailersUrl.get(i);
                            
                if (curURL.indexOf("1080p")!=-1)
                    addTailerRealUrl(bestTrailersUrl,curURL);
            }
            
            if (!bestTrailersUrl.isEmpty())
                return;
        }

        if ((configResolution.equals("1080p")) ||
            (configResolution.equals("720p"))) {
            // Search for 720p
            for (int i=0;i<trailersUrl.size();i++) {
                
                String curURL = trailersUrl.get(i);
                
                if (curURL.indexOf("720p")!=-1)
                    addTailerRealUrl(bestTrailersUrl,curURL);
            }

            if (!bestTrailersUrl.isEmpty())
                return;
        }

        if ((configResolution.equals("1080p")) ||
            (configResolution.equals("720p")) ||
            (configResolution.equals("480p"))) {
            // Search for 480p
            for (int i=0;i<trailersUrl.size();i++) {
                
                String curURL = trailersUrl.get(i);
                
                if (curURL.indexOf("480p")!=-1)
                    addTailerRealUrl(bestTrailersUrl,curURL);
            }

            if (!bestTrailersUrl.isEmpty())
                return;
        }

        // Search for 640
        for (int i=0;i<trailersUrl.size();i++) {
            
            String curURL = trailersUrl.get(i);
            
            if (curURL.indexOf("640")!=-1)
                addTailerRealUrl(bestTrailersUrl,curURL);
        }

        if (!bestTrailersUrl.isEmpty())
            return;
        
        // Search for 480
        for (int i=0;i<trailersUrl.size();i++) {
            
            String curURL = trailersUrl.get(i);
            
            if (curURL.indexOf("480")!=-1)
                addTailerRealUrl(bestTrailersUrl,curURL);
        }
        
    }

    private void addTailerRealUrl(ArrayList<String> bestTrailersUrl,String trailerUrl) {
    
        String trailerRealUrl = getTrailerRealUrl(trailerUrl);
        
        // Check for duplicate
        for (int i=0;i<bestTrailersUrl.size();i++) {
        
            if (bestTrailersUrl.get(i).equals(trailerRealUrl))
                return;
        }
        
        
        bestTrailersUrl.add(trailerRealUrl);
    }

    private String getTrailerRealUrl(String trailerUrl) {
        try {
        
    
            URL url = new URL(trailerUrl);
            HttpURLConnection connection = (HttpURLConnection) (url.openConnection());
            InputStream inputStream = connection.getInputStream();
        
        
            byte buf[] = new byte[1024];
            int len;
            len = inputStream.read(buf);

            // Check if too much data read, that this is the real url already
            if (len==1024)
                return trailerUrl;

        
            String mov = new String(buf);

            int pos = 44;        
            String realUrl = "";
            
            while (mov.charAt(pos)!=0) {
                realUrl += mov.charAt(pos);
                
                pos++;
            }
            
            String absRealURL = getAbsUrl(trailerUrl, realUrl);
            
            return absRealURL;
            
        } catch (Exception e) {
            logger.severe("Error : " + e.getMessage());
            return Movie.UNKNOWN;
        }
    }


    private String getTrailerTitle(String url) {
        int start=url.lastIndexOf('/');
        int end=url.indexOf(".mov",start);
        
        if ((start==-1) || (end==-1))
            return Movie.UNKNOWN;
            
        String title="";
        boolean upper=true;
        
        for (int i=start+1;i<end;i++) {
            if ((url.charAt(i)=='-') || (url.charAt(i)=='_'))
                title += ' ';
            else
            
            if (i==start+1)
                title += Character.toUpperCase(url.charAt(i));
            else
            
                title += url.charAt(i);
        }                        
            
        return title;
    }
    
    private String getAbsUrl(String BaseUrl,String RelativeUrl) {
        try {
       
            URL BaseURL = new URL(BaseUrl);
            URL AbsURL = new URL(BaseURL, RelativeUrl);
            String AbsUrl = AbsURL.toString();
            
            return AbsUrl;
            
        } catch (Exception e) {
            return Movie.UNKNOWN;
        }
    }
    
    private String decodeEscapeICU(String s) {
        String r = "";

        int i=0;
        while (i < s.length()) {
            // Check ICU esacaping
            if ((s.charAt(i) == '%') && (i+5 < s.length()) && (s.charAt(i+1) == 'u')) {

                String value=s.substring(i+2,i+6);
                int intValue= Integer.parseInt(value,16);
                
                // fix for ' char
                if (intValue==0x2019)
                    intValue=0x0027;
                
                char c = (char)intValue;

                r += c;
                i += 6;
            }
            else
            if (s.charAt(i) == '\\') {
                i++;
            }
            else {
                r += s.charAt(i);
                i++;
            }
        }
        
        return r;
    }
    
    private boolean trailerDownload(Movie movie, String trailerUrl, File trailerFile) {
        try {

            logger.fine("AppleTrailers Plugin: Download trailer for " + movie.getBaseName());


            URL url = new URL(trailerUrl);
            HttpURLConnection connection = (HttpURLConnection) (url.openConnection());
            InputStream inputStream = connection.getInputStream();

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                logger.severe("AppleTrailers Plugin: Download Failed");
                return false;
            }

            OutputStream out = new FileOutputStream(trailerFile);
            byte buf[] = new byte[1024*1024];
            int len;
            while ((len = inputStream.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.close();


            return true;

        } catch (Exception e) {
            logger.severe("AppleTrailers Plugin: Download Exception");
            return false;
        }

    }
}
