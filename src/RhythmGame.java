// Rhythm 4K - osu!mania style rhythm game for Nokia Asha 200
// Java ME (CLDC 1.1 / MIDP 2.0), screen 240x320, lanes = keys 1 2 3 *
//
// Optional resources inside the .jar:
//   /chart.txt  lines of "time_ms lane" (lane 0..3), '#' = comment. If missing, a demo chart is generated.
//   /song.mp3 (or /song.mid, /song.amr, /song.wav) played from the start of the chart.
import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.media.*;
import java.io.*;
import java.util.Random;

public class RhythmGame extends MIDlet {
    Game game;

    public void startApp() {
        if (game == null) {
            game = new Game(this);
            Display.getDisplay(this).setCurrent(game);
            new Thread(game).start();
        }
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {
        if (game != null) {
            game.running = false;
            game.stopMusic();
        }
    }

    void quit() {
        destroyApp(true);
        notifyDestroyed();
    }
}

class Game extends Canvas implements Runnable {
    static final int MENU = 0, PLAY = 1, RESULT = 2;
    static final int LANES = 4, LANE_W = 44, FALL = 1100, MAX = 1000;
    static final int PERFECT = 45, GREAT = 90, GOOD = 130;
    static final String[] LABELS = { "1", "2", "3", "*" };

    RhythmGame midlet;
    boolean running = true;
    int state = MENU;

    // chart (sorted by time)
    int[] nt = new int[MAX];
    int[] nl = new int[MAX];
    int[] ns = new int[MAX]; // 0 pending, 1 hit, 2 missed
    int nn = 0;
    int head = 0;

    // play state
    long startTime;
    int offset = 0;
    int score, combo, maxCombo, cP, cG, cGo, cM;
    String judgeText = "";
    int judgeColor = 0xFFFFFF;
    long judgeTime = 0;
    long[] lanePress = new long[LANES];
    Player player;

    Font small = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
    Font large = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE);

    Game(RhythmGame m) {
        midlet = m;
        setFullScreenMode(true);
        loadChart();
    }

    // ---------------- chart ----------------

    void add(int t, int l) {
        if (nn >= MAX) return;
        nt[nn] = t;
        nl[nn] = l;
        ns[nn] = 0;
        nn++;
    }

    void loadChart() {
        nn = 0;
        InputStream in = null;
        try {
            in = getClass().getResourceAsStream("/chart.txt");
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int c;
                while ((c = in.read()) != -1) bo.write(c);
                parse(new String(bo.toByteArray()));
            }
        } catch (Exception e) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
        }
        if (nn == 0) demoChart();
    }

    void parse(String s) {
        int i = 0, len = s.length();
        while (i < len && nn < MAX) {
            int e = s.indexOf('\n', i);
            if (e < 0) e = len;
            String line = s.substring(i, e).trim();
            i = e + 1;
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            int sp = line.indexOf(' ');
            if (sp < 0) continue;
            try {
                int t = Integer.parseInt(line.substring(0, sp).trim());
                int l = Integer.parseInt(line.substring(sp + 1).trim());
                if (l >= 0 && l < LANES) add(t, l);
            } catch (NumberFormatException ex) {
            }
        }
        // insertion sort by time
        for (int a = 1; a < nn; a++) {
            int t = nt[a], l = nl[a], j = a - 1;
            while (j >= 0 && nt[j] > t) {
                nt[j + 1] = nt[j];
                nl[j + 1] = nl[j];
                j--;
            }
            nt[j + 1] = t;
            nl[j + 1] = l;
        }
    }

    Random rand = new Random(12345);

    int rnd(int n) {
        return (rand.nextInt() >>> 1) % n;
    }

    void demoChart() {
        int beat = 60000 / 128; // 128 BPM
        int last = -1;
        for (int s = 0; s < 220 && nn < MAX; s++) {
            int t = 2000 + s * beat / 2;
            boolean on = (s % 2 == 0) || rnd(3) == 0;
            if (!on) continue;
            int l = rnd(LANES);
            if (l == last) l = (l + 1 + rnd(3)) % LANES;
            add(t, l);
            last = l;
            if (s % 8 == 0 && s > 0) add(t, (l + 2) % LANES);
        }
    }

    // ---------------- music ----------------

    void stopMusic() {
        try {
            if (player != null) {
                player.stop();
                player.close();
            }
        } catch (Exception e) {
        }
        player = null;
    }

    void prepareMusic() {
        stopMusic();
        String[] names = { "/song.mp3", "/song.mid", "/song.amr", "/song.wav" };
        String[] types = { "audio/mpeg", "audio/midi", "audio/amr", "audio/x-wav" };
        for (int i = 0; i < names.length; i++) {
            InputStream in = getClass().getResourceAsStream(names[i]);
            if (in == null) continue;
            try {
                player = Manager.createPlayer(in, types[i]);
                player.realize();
                player.prefetch();
                return;
            } catch (Exception e) {
                player = null;
            }
        }
    }

    // ---------------- game logic ----------------

    int songTime() {
        return (int) (System.currentTimeMillis() - startTime) - offset;
    }

    void beginPlay() {
        for (int i = 0; i < nn; i++) ns[i] = 0;
        head = 0;
        score = combo = maxCombo = cP = cG = cGo = cM = 0;
        judgeText = "";
        prepareMusic();
        startTime = System.currentTimeMillis();
        try {
            if (player != null) player.start();
        } catch (Exception e) {
        }
        state = PLAY;
    }

    void judge(String text, int color) {
        judgeText = text;
        judgeColor = color;
        judgeTime = System.currentTimeMillis();
    }

    synchronized void miss() {
        cM++;
        combo = 0;
        judge("MISS", 0xFF4040);
    }

    synchronized void hit(int l) {
        int now = songTime();
        lanePress[l] = System.currentTimeMillis();
        int best = -1, bestD = GOOD + 1;
        for (int i = head; i < nn && nt[i] <= now + GOOD; i++) {
            if (ns[i] != 0 || nl[i] != l) continue;
            int d = Math.abs(nt[i] - now);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) return;
        ns[best] = 1;
        if (bestD <= PERFECT) {
            cP++;
            score += 300;
            judge("PERFECT", 0xFFE040);
        } else if (bestD <= GREAT) {
            cG++;
            score += 200;
            judge("GREAT", 0x40FF80);
        } else {
            cGo++;
            score += 100;
            judge("GOOD", 0x40A0FF);
        }
        combo++;
        if (combo > maxCombo) maxCombo = combo;
    }

    synchronized void update() {
        int now = songTime();
        while (head < nn && (ns[head] != 0 || nt[head] < now - GOOD)) {
            if (ns[head] == 0) {
                ns[head] = 2;
                miss();
            }
            head++;
        }
        if (head >= nn && now > nt[nn - 1] + 1500) {
            stopMusic();
            state = RESULT;
        }
    }

    int accuracy() {
        int total = cP + cG + cGo + cM;
        if (total == 0) return 100;
        return (cP * 300 + cG * 200 + cGo * 100) * 100 / (total * 300);
    }

    String rank() {
        int a = accuracy();
        if (a >= 95) return "S";
        if (a >= 90) return "A";
        if (a >= 80) return "B";
        if (a >= 70) return "C";
        return "D";
    }

    public void run() {
        while (running) {
            long t0 = System.currentTimeMillis();
            if (state == PLAY) update();
            repaint();
            serviceRepaints();
            long dt = System.currentTimeMillis() - t0;
            try {
                Thread.sleep(dt < 33 ? 33 - dt : 1);
            } catch (Exception e) {
            }
        }
    }

    // ---------------- input ----------------

    int laneOf(int k) {
        if (k == KEY_NUM1) return 0;
        if (k == KEY_NUM2) return 1;
        if (k == KEY_NUM3) return 2;
        if (k == KEY_STAR) return 3;
        return -1;
    }

    protected void keyPressed(int k) {
        if (state == PLAY) {
            if (k == KEY_POUND) {
                stopMusic();
                state = MENU;
                return;
            }
            int l = laneOf(k);
            if (l >= 0) hit(l);
            return;
        }
        int a = getGameAction(k);
        boolean ok = (a == FIRE || k == KEY_NUM5);
        if (state == MENU) {
            if (k == KEY_POUND) midlet.quit();
            else if (a == LEFT) offset = Math.max(-300, offset - 10);
            else if (a == RIGHT) offset = Math.min(300, offset + 10);
            else if (ok) beginPlay();
        } else if (ok) {
            state = MENU;
        }
    }

    // ---------------- drawing ----------------

    void center(Graphics g, String s, int w, int y) {
        g.drawString(s, w / 2, y, Graphics.TOP | Graphics.HCENTER);
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        if (state == MENU) paintMenu(g, w, h);
        else if (state == PLAY) paintPlay(g, w, h);
        else paintResult(g, w, h);
    }

    void paintMenu(Graphics g, int w, int h) {
        int sh = small.getHeight();
        g.setFont(large);
        g.setColor(0xFFE040);
        center(g, "RHYTHM 4K", w, 30);
        g.setFont(small);
        g.setColor(0xFFFFFF);
        center(g, "Lanes: 1  2  3  *", w, 90);
        center(g, "5 / OK = Start", w, 90 + sh * 2);
        center(g, "# = Quit / Stop", w, 90 + sh * 3);
        g.setColor(0x80C0FF);
        center(g, "Sync: " + offset + " ms  (< >)", w, 90 + sh * 5);
        g.setColor(0x808080);
        center(g, "Notes: " + nn, w, 90 + sh * 6);
    }

    void paintPlay(Graphics g, int w, int h) {
        long ms = System.currentTimeMillis();
        int now = songTime();
        int x0 = (w - LANES * LANE_W) / 2;
        int hitY = h - 48;

        // lanes
        for (int l = 0; l < LANES; l++) {
            int x = x0 + l * LANE_W;
            g.setColor(ms - lanePress[l] < 120 ? 0x303070 : 0x101018);
            g.fillRect(x, 0, LANE_W, h);
            g.setColor(0x404050);
            g.drawLine(x, 0, x, h);
        }
        g.drawLine(x0 + LANES * LANE_W, 0, x0 + LANES * LANE_W, h);

        // hit line + key labels
        g.setColor(0xFFFFFF);
        g.fillRect(x0, hitY, LANES * LANE_W, 3);
        g.setFont(small);
        g.setColor(0x909090);
        for (int l = 0; l < LANES; l++) {
            g.drawString(LABELS[l], x0 + l * LANE_W + LANE_W / 2, hitY + 8, Graphics.TOP | Graphics.HCENTER);
        }

        // notes
        for (int i = head; i < nn; i++) {
            int dt = nt[i] - now;
            if (dt > FALL) break;
            if (ns[i] != 0) continue;
            int y = hitY - dt * hitY / FALL;
            int x = x0 + nl[i] * LANE_W;
            g.setColor((nl[i] == 0 || nl[i] == 3) ? 0xFF5070 : 0x50C8FF);
            g.fillRect(x + 3, y - 5, LANE_W - 6, 10);
        }

        // HUD
        g.setFont(small);
        g.setColor(0xFFFFFF);
        g.drawString("" + score, 2, 2, Graphics.TOP | Graphics.LEFT);
        g.drawString(accuracy() + "%", w - 2, 2, Graphics.TOP | Graphics.RIGHT);
        if (combo > 1) {
            g.setFont(large);
            g.setColor(0xFFFFFF);
            center(g, "" + combo, w, h / 4);
        }
        if (ms - judgeTime < 450) {
            g.setFont(small);
            g.setColor(judgeColor);
            center(g, judgeText, w, h / 2);
        }
        if (now < 1200) {
            g.setFont(large);
            g.setColor(0xFFE040);
            center(g, "READY", w, h / 2 - 30);
        }
    }

    void paintResult(Graphics g, int w, int h) {
        int sh = small.getHeight();
        g.setFont(large);
        g.setColor(0xFFE040);
        center(g, "RESULT", w, 14);
        center(g, rank(), w, 14 + large.getHeight() + 4);
        g.setFont(small);
        g.setColor(0xFFFFFF);
        int y = 100;
        center(g, "Score: " + score, w, y);
        center(g, "Accuracy: " + accuracy() + "%", w, y + sh);
        center(g, "Max combo: " + maxCombo, w, y + sh * 2);
        g.setColor(0xFFE040);
        center(g, "Perfect: " + cP, w, y + sh * 4);
        g.setColor(0x40FF80);
        center(g, "Great: " + cG, w, y + sh * 5);
        g.setColor(0x40A0FF);
        center(g, "Good: " + cGo, w, y + sh * 6);
        g.setColor(0xFF4040);
        center(g, "Miss: " + cM, w, y + sh * 7);
        g.setColor(0x808080);
        center(g, "5 / OK = Menu", w, h - sh - 6);
    }
}
