package app.xingluo.video;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import app.xingluo.video.engine.HlsDownloader;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.engine.LiveRecorder;
import app.xingluo.video.engine.SegmentedDownloader;
import app.xingluo.video.engine.torrent.TorrentDownloader;
import app.xingluo.video.engine.torrent.TorrentMeta;
import app.xingluo.video.model.MediaBundle;
import app.xingluo.video.stats.ActivityStats;
import okhttp3.Request;
import okhttp3.Response;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class DownloadService extends Service {
    public static final String ENQUEUE = "enqueue", CANCEL = "cancel", PAUSE = "pause", STOP_LIVE = "stop_live";

    private volatile ExecutorService pool;
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicBoolean> liveStops = new ConcurrentHashMap<>();
    private final AtomicInteger count = new AtomicInteger();
    private TaskStore store;
    private int latestStart;

    private record FinishedOutput(Uri uri, String resolution, long bytes, int savedCount, String detailNote) {}

    public static void submit(Context c, String input) { submit(c, input, null, null, null); }

    public static void submit(Context c, String input, String media, String referer, String title) {
        Intent i = new Intent(c, DownloadService.class)
                .setAction(ENQUEUE)
                .putExtra("input", input)
                .putExtra("direct", media)
                .putExtra("referer", referer)
                .putExtra("title", title);
        startServiceCompat(c, i);
    }

    public static void cancel(Context c, String id) {
        c.startService(new Intent(c, DownloadService.class).setAction(CANCEL).putExtra("id", id));
    }

    public static void pause(Context c, String id) {
        c.startService(new Intent(c, DownloadService.class).setAction(PAUSE).putExtra("id", id));
    }

    public static void stopLiveAndSave(Context c, String id) {
        c.startService(new Intent(c, DownloadService.class).setAction(STOP_LIVE).putExtra("id", id));
    }

    private static void startServiceCompat(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new TaskStore(this);
        store.recover();
        MediaFiles.cleanupPending(this);
        int concurrency = new Preferences(this).maxConcurrentTasks();
        pool = Executors.newFixedThreadPool(concurrency);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("downloads", "星落下载进度", NotificationManager.IMPORTANCE_LOW));
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                getContentResolver().delete(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "is_pending=1 AND relative_path=?", new String[]{"Movies/星落/"});
                getContentResolver().delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, "is_pending=1 AND relative_path=?", new String[]{"Download/星落/"});
            } catch (Exception ignored) {}
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStart = startId;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (CANCEL.equals(action)) {
            String id = intent.getStringExtra("id");
            if (id != null) {
                cancelled.add(id);
                TaskStore.Task t = store.get(id);
                if (t != null && t.active()) {
                    t.status = "cancelled";
                    t.detail = "已取消";
                    store.save(t);
                }
            }
            if (count.get() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        if (PAUSE.equals(action)) {
            String id = intent.getStringExtra("id");
            if (id != null) {
                paused.add(id);
                TaskStore.Task t = store.get(id);
                if (t != null && t.active()) {
                    t.status = "paused";
                    t.resumable = true;
                    t.detail = "已暂停（已缓存分片可续传）";
                    store.save(t);
                }
            }
            return START_NOT_STICKY;
        }
        if (STOP_LIVE.equals(action)) {
            String id = intent.getStringExtra("id");
            if (id != null) {
                AtomicBoolean flag = liveStops.get(id);
                if (flag != null) flag.set(true);
            }
            return START_NOT_STICKY;
        }

        String input = intent.getStringExtra("input");
        String direct = intent.getStringExtra("direct");
        for (TaskStore.Task existing : store.all()) {
            if (existing.active()
                    && existing.input.equals(input == null ? "" : input)
                    && existing.direct.equals(direct == null ? "" : direct)) {
                foreground(existing);
                return START_NOT_STICKY;
            }
        }
        TaskStore.Task task = new TaskStore.Task();
        task.input = input == null ? "" : input;
        task.direct = direct == null ? "" : direct;
        task.referer = Objects.toString(intent.getStringExtra("referer"), "");
        task.platform = task.input.equals("分享的视频") ? "分享导入" : LinkInput.platformName(task.input);
        String title = intent.getStringExtra("title");
        if (title != null && !title.isBlank()) task.title = title;
        store.save(task);
        foreground(task);
        count.incrementAndGet();
        pool.execute(() -> {
            runTask(task);
            if (count.decrementAndGet() == 0) {
                new Handler(getMainLooper()).post(() -> {
                    if (count.get() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelfResult(latestStart);
                    }
                });
            }
        });
        return START_NOT_STICKY;
    }

    private void runTask(TaskStore.Task task) {
        File dir = new File(getCacheDir(), "job-" + task.id);
        dir.mkdirs();
        File resumeDir = new File(getFilesDir(), "resume/" + safeResumeKey(task.input.isEmpty() ? task.id : task.input));
        resumeDir.mkdirs();
        Net.Cancellation cancel = () -> cancelled.contains(task.id) || paused.contains(task.id);
        PowerManager.WakeLock wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xingluo:download");
        Preferences prefs = new Preferences(this);
        int quality = prefs.quality();
        int threads = prefs.threadsPerTask();
        boolean preserveResumeOnError = false;
        Uri published = null;

        try {
            wake.acquire(30 * 60 * 1000L);
            Net.check(cancel);

            FinishedOutput output;
            boolean imported = task.direct.startsWith("content://");
            if (imported) {
                output = handleImportedUri(task, dir, cancel);
            } else {
                preserveResumeOnError = prefs.autoResume();
                output = RetryPolicy.run(3, attempt -> {
                    Net.check(cancel);
                    update(task, "resolving", attempt == 1 ? "正在智能识别链接与清晰度…" : "第 " + attempt + " / 3 次重试 · 重新获取最新地址（保留已下载分片）", -1);
                    MediaBundle bundle;
                    if (attempt == 1 && !task.direct.isEmpty()) {
                        bundle = MediaBundle.builder(MediaBundle.Type.VIDEO)
                                .title(task.title)
                                .platform(task.platform)
                                .videoUrl(task.direct)
                                .referer(task.referer)
                                .build();
                    } else {
                        bundle = Resolver.resolveBundle(task.input, prefs, cancel);
                    }
                    applyBundleMetadata(task, bundle);
                    return executeBundle(task, bundle, dir, resumeDir, prefs, quality, threads, cancel);
                }, cancel::cancelled, (next, error) ->
                        update(task, "resolving", "节点波动，保留断点准备第 " + next + " / 3 次重连…", -1),
                        millis -> {
                            long end = System.currentTimeMillis() + millis;
                            while (System.currentTimeMillis() < end) {
                                Net.check(cancel);
                                Thread.sleep(100);
                            }
                        });
            }

            published = output.uri();
            task.uri = published.toString();
            task.bytes = output.bytes();
            task.totalBytes = output.bytes();
            task.resolution = output.resolution();
            task.savedCount = output.savedCount();
            task.speedBps = 0;
            task.resumable = false;
            task.errorCode = "";
            task.errorCause = "";
            deleteDir(resumeDir);

            StringBuilder detail = new StringBuilder();
            detail.append("已保存 · ").append(bytes(output.bytes()));
            if (!output.resolution().isEmpty()) {
                detail.append(output.resolution().contains("×") ? " · 实际 " : " · ").append(output.resolution());
            }
            if (output.detailNote() != null && !output.detailNote().isEmpty()) detail.append("\n").append(output.detailNote());
            else detail.append("\n").append(prefs.locationLabel());

            update(task, "done", detail.toString(), 100);
            new ActivityStats(this).record(System.currentTimeMillis(), task.platform, task.mediaType, task.author, task.tags, output.bytes(), output.resolution(), output.savedCount());
            finishNotification(task, true);
        } catch (CancellationException e) {
            if (paused.contains(task.id)) {
                task.resumable = true;
                update(task, "paused", "已暂停 · 可随时断点续传", task.percent);
            } else {
                if (published != null) MediaFiles.deletePublished(this, published.toString());
                deleteDir(resumeDir);
                update(task, "cancelled", "已取消，临时文件已清理", -1);
            }
        } catch (Exception e) {
            if (published != null) MediaFiles.deletePublished(this, published.toString());
            if (!preserveResumeOnError) deleteDir(resumeDir);
            String message = classifyFriendlyError(e);
            task.errorCode = classifyErrorCode(e, message);
            task.errorCause = message;
            task.resumable = preserveResumeOnError && resumeDir.exists() && resumeDir.list() != null && resumeDir.list().length > 0;
            if (task.resumable) message += "（已保留断点分片，点重试续传）";
            update(task, "failed", message, -1);
            finishNotification(task, false);
        } finally {
            if (wake.isHeld()) wake.release();
            liveStops.remove(task.id);
            cancelled.remove(task.id);
            paused.remove(task.id);
            deleteDir(dir);
        }
    }

    private FinishedOutput handleImportedUri(TaskStore.Task task, File dir, Net.Cancellation cancel) throws IOException {
        File result = new File(dir, "video.mp4");
        update(task, "downloading", "正在把分享的媒体放进收藏袋", -1);
        try (InputStream in = getContentResolver().openInputStream(Uri.parse(task.direct));
             OutputStream out = new FileOutputStream(result)) {
            if (in == null) throw new IOException("无法读取分享的文件");
            byte[] b = new byte[131072];
            int n;
            long size = 0;
            while ((n = in.read(b)) != -1) {
                Net.check(cancel);
                size += n;
                if (size > 4L * 1024 * 1024 * 1024) throw new IOException("文件超过 4 GB");
                out.write(b, 0, n);
            }
        }
        if (task.title.equals("正在寻找视频…")) task.title = "分享的视频";
        MediaFiles.VideoInfo info = MediaFiles.inspect(result);
        Uri published = MediaFiles.publish(this, result, task.title, cancel);
        return new FinishedOutput(published, info.resolutionLabel(), result.length(), 1, "");
    }

    private FinishedOutput executeBundle(TaskStore.Task task, MediaBundle bundle, File dir, File resumeDir,
                                         Preferences prefs, int quality, int threads, Net.Cancellation cancel) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>(bundle.headers);
        if (!bundle.referer.isEmpty() && !headers.containsKey("Referer")) headers.put("Referer", bundle.referer);
        task.threadCount = threads;

        switch (bundle.type) {
            case AUDIO -> {
                List<String> audioUrls = !bundle.audioUrls.isEmpty() ? bundle.audioUrls : bundle.videoUrls;
                if (audioUrls.isEmpty()) throw new IOException("未找到可用的音频流");
                File audio = new File(dir, "audio.m4a");
                update(task, "downloading", "正在极速提取纯音频…", 0);
                SegmentedDownloader.download(audioUrls, headers, audio, resumeDir, threads, cancel,
                        (done, total, speed, active) -> updateProgress(task, done, total, speed, active, 0, 96, "音频下载"));
                update(task, "saving", "正在保存音频到音乐目录", 97);
                Uri uri = MediaFiles.publishAudio(this, audio, task.title, cancel);
                return new FinishedOutput(uri, "AAC 音频", audio.length(), 1, "");
            }
            case GALLERY -> {
                if (bundle.images.isEmpty()) throw new IOException("图集里没有可保存的图片");
                int totalCount = bundle.images.size();
                task.itemCount = totalCount;
                AtomicInteger doneCount = new AtomicInteger();
                AtomicLong totalBytes = new AtomicLong();
                AtomicReference<Uri> firstUri = new AtomicReference<>();
                ExecutorService imgPool = Executors.newFixedThreadPool(Math.min(4, Math.max(1, threads)));
                List<Future<Void>> futures = new ArrayList<>();
                for (int i = 0; i < totalCount; i++) {
                    final int idx = i;
                    final MediaBundle.ImageItem img = bundle.images.get(i);
                    futures.add(imgPool.submit(() -> {
                        Net.check(cancel);
                        File imgFile = new File(dir, "img_" + idx);
                        List<String> urls = new ArrayList<>();
                        urls.add(img.url);
                        urls.addAll(img.backupUrls);
                        SegmentedDownloader.download(urls, headers, imgFile, new File(resumeDir, "img_" + idx), 1, cancel, null);
                        String ext = guessImageExt(img.url, img.filename);
                        String name = (img.filename != null && !img.filename.isBlank())
                                ? LinkInput.safeName(img.filename) + (img.filename.contains(".") ? "" : ext)
                                : LinkInput.safeName(task.title) + "_p" + (idx + 1) + ext;
                        String mime = ext.equals(".png") ? "image/png" : ext.equals(".webp") ? "image/webp" : "image/jpeg";
                        Uri published = MediaFiles.publishImage(this, imgFile, name, mime, bundle.subfolder, cancel);
                        firstUri.compareAndSet(null, published);
                        totalBytes.addAndGet(imgFile.length());
                        imgFile.delete();
                        int finished = doneCount.incrementAndGet();
                        int pct = (int) (finished * 100L / totalCount);
                        update(task, "downloading", "正在保存图集 " + finished + " / " + totalCount + " 张", pct);
                        return null;
                    }));
                }
                try {
                    for (Future<Void> f : futures) f.get();
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof Exception ce) throw ce;
                    throw new IOException(ex.getCause());
                } finally {
                    imgPool.shutdownNow();
                }
                String subNote = bundle.subfolder.isEmpty() ? prefs.locationLabel() : "Pictures/星落/" + bundle.subfolder;
                return new FinishedOutput(firstUri.get(), "图集 " + totalCount + "P", totalBytes.get(), totalCount, subNote);
            }
            case LIVE -> {
                String liveUrl = !bundle.liveUrl.isEmpty() ? bundle.liveUrl : bundle.hlsUrl;
                if (liveUrl.isEmpty()) throw new IOException("未找到可用的直播流");
                AtomicBoolean stopFlag = new AtomicBoolean(false);
                liveStops.put(task.id, stopFlag);
                File liveOut = new File(dir, "live.mp4");
                update(task, "recording", "正在录制直播（点“停止并保存”可随时保存）", -1);
                LiveRecorder.record(liveUrl, headers, liveOut, quality, stopFlag, cancel,
                        (done, total, speed, active) -> {
                            task.bytes = done;
                            task.speedBps = speed;
                            update(task, "recording", "录制中 · 已收 " + bytes(done) + " · " + bytes(speed) + "/s", -1);
                        });
                update(task, "saving", "正在封装并保存直播录像", 97);
                MediaFiles.VideoInfo info = MediaFiles.inspect(liveOut);
                Uri uri = MediaFiles.publish(this, liveOut, task.title, cancel);
                return new FinishedOutput(uri, info.resolutionLabel(), liveOut.length(), 1, "直播录制片段已保存");
            }
            case CLOUD_FILE -> {
                if (bundle.cloudFiles.isEmpty()) throw new IOException("网盘分享中未发现可下载文件");
                task.itemCount = bundle.cloudFiles.size();
                Uri firstUri = null;
                long sumBytes = 0;
                int cloudThreads = task.platform.contains("百度") ? Math.min(3, threads) : threads;
                for (int i = 0; i < bundle.cloudFiles.size(); i++) {
                    MediaBundle.CloudFileItem cf = bundle.cloudFiles.get(i);
                    File f = new File(dir, LinkInput.safeName(cf.name));
                    Map<String, String> cfHeaders = new LinkedHashMap<>(headers);
                    cfHeaders.putAll(cf.headers);
                    final int idx = i;
                    SegmentedDownloader.download(cf.downloadUrl, cfHeaders, f, new File(resumeDir, "cf_" + idx), cloudThreads, cancel,
                            (done, total, speed, active) -> updateProgress(task, done, total, speed, active, 0, 95, "网盘加速"));
                    update(task, "saving", "正在保存到下载目录", 97);
                    String lower = cf.name.toLowerCase(Locale.ROOT);
                    Uri uri = lower.endsWith(".mp4")
                            ? MediaFiles.publish(this, f, cf.name.replaceAll("(?i)\\.mp4$", ""), cancel)
                            : MediaFiles.publishGeneric(this, f, cf.name, guessMime(cf.name), bundle.subfolder, cancel);
                    if (firstUri == null) firstUri = uri;
                    sumBytes += f.length();
                }
                return new FinishedOutput(firstUri, bundle.cloudFiles.size() > 1 ? bundle.cloudFiles.size() + " 个文件" : "网盘直传", sumBytes, bundle.cloudFiles.size(), bundle.extraInfo);
            }
            case TORRENT -> {
                TorrentMeta meta;
                if (LinkInput.isMagnet(bundle.torrentUriOrPath)) {
                    meta = TorrentMeta.fromMagnet(bundle.torrentUriOrPath);
                } else {
                    File torrentFile = new File(dir, "meta.torrent");
                    SegmentedDownloader.download(bundle.torrentUriOrPath, headers, torrentFile, resumeDir, 1, cancel, null);
                    meta = TorrentMeta.fromTorrentFile(torrentFile);
                }
                File tOut = new File(dir, "torrent_out");
                tOut.mkdirs();
                update(task, "downloading", "正在解析种子与节点…", 0);
                TorrentDownloader.DownloadResult res = TorrentDownloader.download(meta, tOut, cancel, (done, total) ->
                        updateProgress(task, done, total, 0, 4, 0, 95, "BT/WebSeed"));
                File primary = res.primaryFile();
                if (primary == null || !primary.exists()) throw new IOException("种子未产生有效文件");
                Uri uri = primary.getName().toLowerCase(Locale.ROOT).endsWith(".mp4")
                        ? MediaFiles.publish(this, primary, task.title, cancel)
                        : MediaFiles.publishGeneric(this, primary, primary.getName(), guessMime(primary.getName()), "种子下载", cancel);
                return new FinishedOutput(uri, res.mode, res.totalBytes, res.files.size(), "");
            }
            default -> {
                File finalVideo;
                if (!bundle.hlsUrl.isEmpty() && bundle.videoUrls.isEmpty()) {
                    finalVideo = new File(dir, "hls_merged.mp4");
                    update(task, "downloading", "正在解析并拉取高清流媒体分片…", 0);
                    HlsDownloader.download(bundle.hlsUrl, headers, finalVideo, new File(dir, "hls_work"), quality, cancel,
                            (done, total, speed, active) -> updateProgress(task, done, total, speed, active, 0, 94, "流媒体加速"));
                } else {
                    if (bundle.videoUrls.isEmpty()) throw new IOException("未获取到可下载的视频地址");
                    File video = new File(dir, "video.mp4");
                    boolean hasAudio = !bundle.audioUrls.isEmpty();
                    update(task, "downloading", hasAudio ? "多线程下载高清画面…" : "多线程极速下载中…", 0);
                    SegmentedDownloader.download(bundle.videoUrls, headers, video, new File(resumeDir, "v"), threads, cancel,
                            (done, total, speed, active) -> updateProgress(task, done, total, speed, active, 0, hasAudio ? 72 : 95, threads + "线程"));
                    if (!hasAudio) {
                        finalVideo = video;
                    } else {
                        File audio = new File(dir, "audio.m4a");
                        File merged = new File(dir, "merged.mp4");
                        SegmentedDownloader.download(bundle.audioUrls, headers, audio, new File(resumeDir, "a"), Math.min(2, threads), cancel,
                                (done, total, speed, active) -> updateProgress(task, done, total, speed, active, 72, 22, "音轨"));
                        update(task, "merging", "画面与声音，正在相遇…", 95);
                        MediaFiles.mux(video, audio, merged, cancel);
                        finalVideo = merged;
                    }
                }
                update(task, "saving", "正在写入保存位置", 97);
                MediaFiles.VideoInfo info = MediaFiles.inspect(finalVideo);
                Uri published = MediaFiles.publish(this, finalVideo, task.title, cancel);
                String note = prefs.locationLabel();
                if (info.belowPreference(quality)) note = "低于首选 " + quality + "p · " + note;
                return new FinishedOutput(published, info.resolutionLabel(), finalVideo.length(), 1, note);
            }
        }
    }

    private void applyBundleMetadata(TaskStore.Task task, MediaBundle bundle) {
        if (bundle.title != null && !bundle.title.isBlank()) task.title = bundle.title;
        if (bundle.platform != null && !bundle.platform.isBlank()) task.platform = bundle.platform;
        task.mediaType = bundle.type.name();
        task.author = bundle.author;
        task.tags = new ArrayList<>(bundle.tags);
        task.watermarkFree = bundle.watermarkFree;
        if (bundle.estimatedBytes > 0) task.totalBytes = bundle.estimatedBytes;
        store.save(task);
    }

    private void updateProgress(TaskStore.Task task, long done, long total, long speedBps, int activeThreads, int startPct, int spanPct, String tag) {
        task.bytes = done;
        if (total > 0) task.totalBytes = total;
        task.speedBps = speedBps;
        task.threadCount = activeThreads;
        int pct = total > 0 ? Math.min(99, startPct + (int) (done * spanPct / total)) : -1;
        StringBuilder msg = new StringBuilder();
        msg.append(bytes(done));
        if (total > 0) msg.append(" / ").append(bytes(total));
        if (speedBps > 0) msg.append(" · ").append(bytes(speedBps)).append("/s");
        msg.append(" · ").append(tag);
        update(task, "downloading", msg.toString(), pct);
    }

    private static String classifyFriendlyError(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "网络暂时不可用或域名无法解析，点「诊断原因」排查";
        if (e instanceof java.net.SocketTimeoutException) return "网络响应超时，可点「诊断原因」检查 VPN 或节点";
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? "暂时没接住这个资源，可点重试或诊断原因" : m;
    }

    private static String classifyErrorCode(Exception e, String msg) {
        if (e instanceof java.net.UnknownHostException) return "DNS_OR_OFFLINE";
        if (e instanceof java.net.SocketTimeoutException) return "TIMEOUT";
        if (msg.contains("403") || msg.contains("412") || msg.contains("登录") || msg.contains("受限")) return "AUTH_OR_403";
        if (msg.contains("429")) return "RATE_LIMIT";
        if (msg.contains("DRM") || msg.contains("加密")) return "DRM";
        if (msg.contains("空间") || msg.contains("文件夹")) return "STORAGE";
        return "ERROR";
    }

    private static String guessImageExt(String url, String filename) {
        String s = (filename + " " + url).toLowerCase(Locale.ROOT);
        if (s.contains(".png")) return ".png";
        if (s.contains(".webp")) return ".webp";
        return ".jpg";
    }

    private static String guessMime(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        if (low.endsWith(".mp4")) return "video/mp4";
        if (low.endsWith(".mkv")) return "video/x-matroska";
        if (low.endsWith(".mp3")) return "audio/mpeg";
        if (low.endsWith(".m4a")) return "audio/mp4";
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) return "image/jpeg";
        if (low.endsWith(".png")) return "image/png";
        if (low.endsWith(".zip")) return "application/zip";
        if (low.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String safeResumeKey(String input) {
        return Integer.toHexString(input.hashCode());
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) deleteDir(f);
        dir.delete();
    }

    public static String bytes(long n) {
        if (n < 0) return "0 KB";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.CHINA, "%.0f KB", n / 1024d);
        if (n < 1024L * 1024L * 1024L) return String.format(Locale.CHINA, "%.1f MB", n / 1048576d);
        return String.format(Locale.CHINA, "%.2f GB", n / 1073741824d);
    }

    private void update(TaskStore.Task task, String state, String detail, int percent) {
        task.status = state;
        task.detail = detail;
        task.percent = percent;
        store.save(task);
        if (task.active()) {
            getSystemService(NotificationManager.class).notify(1, notification(task, true));
        }
    }

    private Notification notification(TaskStore.Task task, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, "downloads")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(task.title)
                .setContentText(task.detail)
                .setStyle(new Notification.BigTextStyle().bigText(task.detail))
                .setContentIntent(content)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setAutoCancel(!ongoing);
        if (ongoing) {
            b.setProgress(100, Math.max(0, task.percent), task.percent < 0);
            Intent cancel = new Intent(this, DownloadService.class).setAction(CANCEL).putExtra("id", task.id);
            b.addAction(new Notification.Action.Builder(null, "取消",
                    PendingIntent.getService(this, task.id.hashCode(), cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        return b.build();
    }

    private void foreground(TaskStore.Task task) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification(task, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification(task, true));
        }
    }

    private void finishNotification(TaskStore.Task task, boolean success) {
        getSystemService(NotificationManager.class).notify(task.id.hashCode(), notification(task, false));
    }

    @Override public void onTimeout(int startId, int fgsType) {
        for (TaskStore.Task t : store.all()) if (t.active()) cancelled.add(t.id);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        for (TaskStore.Task t : store.all()) if (t.active()) cancelled.add(t.id);
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
