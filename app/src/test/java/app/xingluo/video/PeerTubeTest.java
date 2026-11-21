package app.xingluo.video;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PeerTubeTest {
    private static final String ID="d8099a47-2f56-46c1-99d3-a64fa1934651";
    private static final String PAGE="https://tube.test/videos/watch/"+ID+"?start=1";

    @Test public void resolvesFederatedWatchAndShortUrls() {
        assertEquals("https://tube.test/api/v1/videos/"+ID,PeerTube.apiUrl(PAGE));
        assertEquals("https://other.test/api/v1/videos/sFhdfdsN1dbgy41XqRawdH",
                PeerTube.apiUrl("https://other.test/w/sFhdfdsN1dbgy41XqRawdH"));
        assertNull(PeerTube.apiUrl("https://tube.test/w/plain-page"));
    }

    @Test public void choosesDownloadableMp4UsingPreferredResolution() throws Exception {
        JSONObject response = new JSONObject("""
            {"uuid":"test","name":"Demo","downloadEnabled":true,"files":[
              {"resolution":{"id":1080},"fileUrl":"https://cdn.test/1080.mp4","fileDownloadUrl":"https://tube.test/download/1080"},
              {"resolution":{"id":720},"fileUrl":"https://cdn.test/720.webm","fileDownloadUrl":"https://tube.test/download/webm"},
              {"resolution":{"id":480},"fileUrl":"https://cdn.test/480.mp4","fileDownloadUrl":"https://tube.test/download/480"},
              {"resolution":{"id":0},"fileUrl":"https://cdn.test/audio.mp4","fileDownloadUrl":"https://tube.test/download/audio"}
            ]}
            """);
        assertTrue(PeerTube.describesVideo(response));
        assertEquals("https://tube.test/download/480",PeerTube.select(response,PAGE,720).videoUrl);
        assertEquals("https://tube.test/download/1080",PeerTube.select(response,PAGE,1080).videoUrl);
        assertEquals(java.util.List.of("https://tube.test/download/1080","https://cdn.test/1080.mp4"),
                PeerTube.select(response,PAGE,1080).videoUrls);
        assertEquals("https://tube.test/download/480",PeerTube.select(response,PAGE,360).videoUrl);
        response.put("downloadEnabled",false);
        var error=assertThrows(IllegalArgumentException.class,()->PeerTube.select(response,PAGE,1080));
        assertTrue(error.getMessage().contains("作者未开启"));
    }

    @Test public void doesNotTreatStreamingPlaylistFilesAsDownloadableMp4() throws Exception {
        JSONObject response = new JSONObject("""
            {"uuid":"test","name":"Demo","downloadEnabled":true,"files":[],
             "streamingPlaylists":[{"files":[{"resolution":{"id":1080},"fileUrl":"https://cdn.test/1080-fragmented.mp4"}]}]}
            """);
        assertThrows(IllegalArgumentException.class,()->PeerTube.select(response,PAGE,1080));
    }
}
