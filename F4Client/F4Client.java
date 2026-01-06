import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Random;
import javax.swing.Timer;

public class F4Client extends JFrame {
    private JPanel boardPanel;
    private JLabel lblStatus, lblMyName, lblOppName, lblTurn;

    private JPanel pnlMyColor, pnlOppColor;

    private char[][] board;
    private int rows = 6, cols = 7;
    private char mySym = 'X', oppSym = 'O';
    private Color myColor = Color.GRAY, oppColor = Color.GRAY;
    private String myName, oppName = "???";

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String serverIp = "localhost:4444";

    private volatile boolean isRunning = false;
    private volatile boolean expectingDisconnect = false;
    private boolean isOffline = false;
    private boolean isMyTurn = false;
    private boolean gameStarted = false;

    private Timer scanTimer;
    private int dotCount = 0;

    public static void main(String[] args) {
        if(args.length < 1) {
            JOptionPane.showMessageDialog(null, "Manca il nome giocatore!");
            System.exit(1);
        }
        SwingUtilities.invokeLater(() -> new F4Client(args[0]));
    }

    public F4Client(String name) {
        super("Forza 4 Client - " + name);
        this.myName = name;
        setupGUI();
        SwingUtilities.invokeLater(this::showMenu);
    }

    private void setupGUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(850, 600);
        setLayout(new BorderLayout());

        board = new char[rows][cols];
        clearBoard();

        boardPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawBoard(g);
            }
        };
        boardPanel.setBackground(Color.DARK_GRAY);
        boardPanel.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) { handleInput(e.getX()); }
        });
        add(boardPanel, BorderLayout.CENTER);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(new EmptyBorder(10,10,10,10));
        side.setPreferredSize(new Dimension(220, 0));

        JLabel t = new JLabel("INFO PARTITA");
        t.setFont(new Font("Arial", Font.BOLD, 18));
        side.add(t);
        side.add(Box.createVerticalStrut(20));

        side.add(new JLabel("Tu: " + myName));
        pnlMyColor = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawColorBox(g, myColor, mySym);
            }
        };
        pnlMyColor.setPreferredSize(new Dimension(40,40));
        pnlMyColor.setMaximumSize(new Dimension(40,40));
        pnlMyColor.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(pnlMyColor);

        side.add(Box.createVerticalStrut(10));

        lblOppName = new JLabel("Avversario: ???");
        side.add(lblOppName);
        pnlOppColor = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawColorBox(g, oppColor, oppSym);
            }
        };
        pnlOppColor.setPreferredSize(new Dimension(40,40));
        pnlOppColor.setMaximumSize(new Dimension(40,40));
        pnlOppColor.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(pnlOppColor);

        side.add(Box.createVerticalStrut(30));
        lblTurn = new JLabel("Stato: Menu");
        lblTurn.setFont(new Font("Arial", Font.BOLD, 14));
        side.add(lblTurn);

        add(side, BorderLayout.EAST);

        lblStatus = new JLabel("Benvenuto");
        lblStatus.setBorder(new EmptyBorder(5,5,5,5));
        add(lblStatus, BorderLayout.SOUTH);

        scanTimer = new Timer(500, e -> updateAnim());
        scanTimer.start();

        setVisible(true);
    }

    private void drawColorBox(Graphics g, Color c, char sym) {
        g.setColor(c);
        g.fillRect(0,0,40,40);
        g.setColor(Color.BLACK);
        g.drawRect(0,0,39,39);

        if (sym != '?' && sym != ' ') {
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setFont(new Font("SansSerif", Font.BOLD, 24));
            FontMetrics fm = g2.getFontMetrics();
            Rectangle2D r = fm.getStringBounds(String.valueOf(sym), g2);
            int x = (40 - (int)r.getWidth()) / 2;
            int y = (40 - (int)r.getHeight()) / 2 + fm.getAscent();
            g2.drawString(String.valueOf(sym), x, y);
        }
    }

    private void showMenu() {
        fullReset();
        String[] opts = {"Online", "Offline (vs CPU)"};
        int ch = JOptionPane.showOptionDialog(this, "Scegli modalità", "Menu",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);

        if (ch == 0) startOnline();
        else if (ch == 1) startOffline();
        else System.exit(0);
    }

    private void fullReset() {
        isRunning = false;
        expectingDisconnect = false;
        try { if(socket!=null) socket.close(); } catch(Exception e){}

        rows = 6; cols = 7;
        board = new char[rows][cols];
        clearBoard();

        isOffline = false;
        gameStarted = false;

        myColor = Color.LIGHT_GRAY;
        oppColor = Color.LIGHT_GRAY;
        mySym = '?';
        oppSym = '?';

        lblOppName.setText("Avversario: ???");
        lblTurn.setText("Stato: Menu");
        lblStatus.setText("In attesa...");

        pnlMyColor.repaint();
        pnlOppColor.repaint();
        boardPanel.repaint();
    }

    private void clearBoard() {
        for(char[] r : board) Arrays.fill(r, ' ');
    }

    private void startOnline() {
        String res = JOptionPane.showInputDialog(this, "Indirizzo Server:", serverIp);
        if(res == null) { showMenu(); return; }
        serverIp = res;

        lblStatus.setText("Connessione...");
        isRunning = true;
        expectingDisconnect = false;
        new Thread(this::networkLoop).start();
    }

    private void networkLoop() {
        while(isRunning) {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(serverIp.split(":")[0], 4444), 3000);

                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                out.println(myName);
                SwingUtilities.invokeLater(() -> lblStatus.setText("Connesso! Attesa configurazione..."));

                String msg;
                while ((msg = in.readLine()) != null) {
                    if (msg.startsWith("WIN") || msg.startsWith("DRAW") || msg.startsWith("EXIT_OPPONENT_LEFT")) {
                        expectingDisconnect = true;
                    }
                    processMessage(msg);
                }
                throw new IOException("Server chiuso");

            } catch (IOException e) {
                if (expectingDisconnect) {
                    isRunning = false;
                    return;
                }
                try { socket.close(); } catch(Exception ex){}
                if(!gameStarted) {
                    try { Thread.sleep(1000); } catch(Exception ex){}
                } else {
                    isRunning = false;
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Disconnessione Server!");
                        showMenu();
                    });
                    return;
                }
            }
        }
    }

    private void processMessage(String msg) {
        SwingUtilities.invokeLater(() -> {
            String[] p = msg.split(" ");
            String cmd = p[0];

            switch(cmd) {
                case "CONFIG":
                    rows = Integer.parseInt(p[1]);
                    cols = Integer.parseInt(p[2]);
                    mySym = p[3].charAt(0);
                    if (p[4].equals("RED")) { myColor=Color.RED; oppColor=Color.YELLOW; }
                    else { myColor=Color.YELLOW; oppColor=Color.RED; }

                    board = new char[rows][cols];
                    clearBoard();

                    pnlMyColor.repaint();
                    pnlOppColor.repaint();
                    boardPanel.repaint();
                    break;

                case "START":
                    gameStarted = true;
                    oppSym = p[2].charAt(0);
                    String n = "";
                    for(int i=1; i<p.length-1; i++) n += p[i] + " ";
                    oppName = n.trim();

                    lblOppName.setText("Avversario: " + oppName);
                    pnlOppColor.repaint();
                    lblStatus.setText("Partita Iniziata!");
                    boardPanel.repaint();
                    break;

                case "YOUR_TURN":
                    isMyTurn = true;
                    lblTurn.setText("TOCCA A TE");
                    lblTurn.setForeground(new Color(0,100,0));
                    break;
                case "WAIT_TURN":
                    isMyTurn = false;
                    lblTurn.setText("Turno avversario");
                    lblTurn.setForeground(Color.RED);
                    break;
                case "MOVED":
                    int r = Integer.parseInt(p[1]);
                    int c = Integer.parseInt(p[2]);
                    char s = p[3].charAt(0);
                    board[r][c] = s;
                    boardPanel.repaint();
                    break;

                case "WIN":
                case "DRAW":
                case "EXIT_OPPONENT_LEFT":
                    isRunning = false;
                    String txt = "Pareggio!";
                    if(cmd.equals("WIN")) {
                        String winner = "";
                        for(int i=1; i<p.length; i++) winner += p[i] + " ";
                        txt = "Vittoria: " + winner.trim();
                    } else if (cmd.equals("EXIT_OPPONENT_LEFT")) {
                        txt = "Avversario disconnesso.";
                    }
                    JOptionPane.showMessageDialog(this, txt);
                    showMenu();
                    break;
            }
        });
    }

    private void startOffline() {
        isOffline = true;
        gameStarted = true;

        myColor = Color.RED;
        oppColor = Color.YELLOW;

        Random rnd = new Random();
        String abc = "ABCDEFGHILMNOPQRSTUVZ";
        mySym = abc.charAt(rnd.nextInt(abc.length()));
        do { oppSym = abc.charAt(rnd.nextInt(abc.length())); } while(oppSym == mySym);

        pnlMyColor.repaint();
        pnlOppColor.repaint();
        lblOppName.setText("Avversario: CPU");

        isMyTurn = true;
        lblTurn.setText("TOCCA A TE");
        boardPanel.repaint();
    }

    private void handleInput(int x) {
        if (!isMyTurn) return;
        int cw = boardPanel.getWidth() / cols;
        int col = x / cw;
        if (col < 0 || col >= cols) return;

        if (isOffline) {
            playOffline(col);
        } else {
            out.println("MOVE " + col);
            isMyTurn = false;
            lblTurn.setText("Attendi...");
        }
    }

    private void playOffline(int c) {
        if (board[0][c] != ' ') return;
        dropToken(c, mySym);
        boardPanel.repaint();

        if (checkLocalWin(mySym)) { JOptionPane.showMessageDialog(this, "Vittoria: " + myName); showMenu(); return; }

        isMyTurn = false;
        lblTurn.setText("CPU pensa...");
        new Timer(800, e -> { ((Timer)e.getSource()).stop(); cpuMove(); }).start();
    }

    private void cpuMove() {
        Random r = new Random();
        int c; do { c = r.nextInt(cols); } while(board[0][c] != ' ');
        dropToken(c, oppSym);
        boardPanel.repaint();

        if (checkLocalWin(oppSym)) { JOptionPane.showMessageDialog(this, "Vittoria: CPU"); showMenu(); return; }

        isMyTurn = true;
        lblTurn.setText("TOCCA A TE");
    }

    private void dropToken(int c, char s) {
        for(int r=rows-1; r>=0; r--) if(board[r][c]==' ') { board[r][c]=s; return; }
    }
    private boolean checkLocalWin(char s) {
        for(int r=0;r<rows;r++) for(int c=0;c<cols;c++) {
            if(checkDir(r,c,s,1,0)||checkDir(r,c,s,0,1)||checkDir(r,c,s,1,1)||checkDir(r,c,s,1,-1)) return true;
        } return false;
    }
    private boolean checkDir(int r, int c, char s, int dr, int dc) {
        int cnt=0;
        for(int i=0; i<4; i++) {
            int nr=r+i*dr, nc=c+i*dc;
            if(nr>=0&&nr<rows&&nc>=0&&nc<cols&&board[nr][nc]==s) cnt++; else break;
        } return cnt==4;
    }

    private void updateAnim() {
        dotCount = (dotCount + 1) % 4;
        String dots = ".".repeat(dotCount);
        if (isRunning && !gameStarted) {
            if(socket == null || socket.isClosed()) lblStatus.setText("Cerco Server" + dots);
            else lblStatus.setText("Attesa avversario" + dots);

            if(socket != null && !socket.isClosed()) lblOppName.setText("Scanning" + dots);
        }
    }

    private void drawBoard(Graphics g) {
        int w = boardPanel.getWidth(), h = boardPanel.getHeight();
        int cw = w/cols, ch = h/rows;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(new Color(0,50,150));
        g.fillRect(0,0,cols*cw, rows*ch);

        for(int r=0;r<rows;r++) for(int c=0;c<cols;c++) {
            char s = board[r][c];
            if(s==mySym) g.setColor(myColor); else if(s==oppSym) g.setColor(oppColor); else g.setColor(Color.WHITE);

            int p = 5;
            g.fillOval(c*cw+p, r*ch+p, cw-p*2, ch-p*2);

            if(s!=' ') {
                g.setColor(Color.BLACK);
                int fontSize = Math.max(16, (int)(ch * 0.5));
                g.setFont(new Font("SansSerif", Font.BOLD, fontSize));

                FontMetrics fm = g.getFontMetrics();
                Rectangle2D b = fm.getStringBounds(String.valueOf(s), g);
                int tx = c*cw + (cw - (int)b.getWidth()) / 2;
                int ty = r*ch + (ch - (int)b.getHeight()) / 2 + fm.getAscent();
                g.drawString(""+s, tx, ty);
            }
        }
    }
}