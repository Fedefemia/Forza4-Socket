import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.net.*;
import java.util.Arrays;
import javax.swing.Timer;

public class F4Server extends JFrame{
    private JLabel lblStatus;
    private JLabel lblP1, lblP2;
    private JPanel boardPanel;
    private JTextArea logArea;

    private final int port = 4444;
    private int rows, cols;
    private char sym1, sym2;

    private ServerSocket serverSocket;
    private Player p1, p2;
    private char[][] board;

    private Timer scanTimer;
    private int dotCount = 0;

    public static void main (String[] args) {
        if (args.length < 4) {
            JOptionPane.showMessageDialog(null, "Parametri mancanti!\nUso: java F4Server <righe> <colonne> <sim1> <sim2>");
            System.exit(1);
        }
        try {
            int r = Integer.parseInt(args[0]);
            int c = Integer.parseInt(args[1]);
            char s1 = args[2].charAt(0);
            char s2 = args[3].charAt(0);
            if (s1 == s2) throw new Exception("Simboli uguali");

            SwingUtilities.invokeLater(() -> new F4Server(r, c, s1, s2));
        } catch (Exception e) {
            System.exit(1);
        }
    }

    public F4Server(int rows, int cols, char s1, char s2) {
        super("F4 Server Monitor");
        this.rows = rows;
        this.cols = cols;
        this.sym1 = s1;
        this.sym2 = s2;

        setupGUI();
        new Thread(this::serverLoop).start();
    }

    private void setupGUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLayout(new BorderLayout());

        JPanel header = new JPanel(new GridLayout(2, 1));
        header.setBorder(new EmptyBorder(10,10,10,10));
        header.setBackground(new Color(230,230,230));

        lblStatus = new JLabel("Server Attivo - Porta " + port);
        lblStatus.setFont(new Font("Arial", Font.BOLD, 16));
        lblStatus.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(lblStatus);

        JPanel playersPanel = new JPanel(new GridLayout(1, 2));
        lblP1 = new JLabel("Player 1: Scanning...");
        lblP2 = new JLabel("Player 2: Scanning...");
        lblP1.setHorizontalAlignment(SwingConstants.CENTER);
        lblP2.setHorizontalAlignment(SwingConstants.CENTER);
        playersPanel.add(lblP1);
        playersPanel.add(lblP2);
        header.add(playersPanel);
        add(header, BorderLayout.NORTH);

        board = new char[rows][cols];
        for(char[] r : board) Arrays.fill(r, ' ');

        boardPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                drawBoard(g);
            }
        };
        boardPanel.setBackground(Color.LIGHT_GRAY);
        add(boardPanel, BorderLayout.CENTER);

        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(250, 0));
        scroll.setBorder(BorderFactory.createTitledBorder("Log"));
        add(scroll, BorderLayout.EAST);

        scanTimer = new Timer(500, e -> animateLabels());
        scanTimer.start();

        setVisible(true);
    }

    private void serverLoop() {
        try {
            serverSocket = new ServerSocket(port);
            log("Server avviato.");

            while (true) {
    p1 = null;
    p2 = null;
    board = new char[rows][cols];
    for(char[] r : board) Arrays.fill(r, ' ');

    SwingUtilities.invokeLater(() -> {
        boardPanel.repaint();
        lblP1.setText("Player 1: In attesa...");
        lblP2.setText("Player 2: In attesa...");
    });
    log("--- In attesa di nuova partita ---");

    try {
        // Connessione Giocatore 1
        p1 = acceptPlayer(sym1);
        
        SwingUtilities.invokeLater(() -> lblP1.setText("P1: " + p1.name));
        p1.send("CONFIG " + rows + " " + cols + " " + sym1 + " RED");

        // Connessione Giocatore 2
        p2 = acceptPlayer(sym2);
        
        SwingUtilities.invokeLater(() -> lblP2.setText("P2: " + p2.name));
        p2.send("CONFIG " + rows + " " + cols + " " + sym2 + " YELLOW");

        // Risoluzione conflitto nomi
        if (p1.name.equals(p2.name)) { 
            p1.name += "1"; 
            p2.name += "2"; 
        }

        log("Partita: " + p1.name + " vs " + p2.name);
        p1.send("START " + p2.name + " " + sym2);
        p2.send("START " + p1.name + " " + sym1);

        // Avvio logica di gioco
        playMatch();

    } catch (IOException e) {
        log("Errore connessione: " + e.getMessage());
    }

    log("Chiusura connessioni...");
    try { Thread.sleep(200); } catch(InterruptedException e) {}
    if (p1 != null) p1.close();
    if (p2 != null) p2.close();
}

        } catch (IOException e) {
            log("Errore Server: " + e.getMessage());
        }
    }

    private Player acceptPlayer(char symbol) throws IOException {
        Socket s = serverSocket.accept();
        Player p = new Player(s, symbol);
        log("Connesso: " + p.name);
        return p;
    }

    private void playMatch() {
        Player current = p1;
        Player other = p2;
        boolean gameRunning = true;

        while (gameRunning) {
            try {
                current.send("YOUR_TURN");
                other.send("WAIT_TURN");

                String line = current.in.readLine();
                if (line == null) throw new IOException("Client disconnected");

                if (line.startsWith("MOVE")) {
                    int col = Integer.parseInt(line.split(" ")[1]);
                    if (isValidMove(col)) {
                        int row = dropToken(col, current.symbol);

                        SwingUtilities.invokeLater(boardPanel::repaint);
                        log(current.name + " -> " + col);

                        broadcast("MOVED " + row + " " + col + " " + current.symbol);

                        if (checkWin(row, col, current.symbol)) {
                            broadcast("WIN " + current.name);
                            log("Vittoria: " + current.name);
                            gameRunning = false;
                        } else if (isBoardFull()) {
                            broadcast("DRAW");
                            log("Pareggio");
                            gameRunning = false;
                        } else {
                            Player temp = current;
                            current = other;
                            other = temp;
                        }
                    }
                }
            } catch (Exception e) {
                log("Disconnessione in gioco: " + e.getMessage());
                broadcast("EXIT_OPPONENT_LEFT");
                gameRunning = false;
            }
        }
    }

    private void broadcast(String msg) {
        if (p1 != null) p1.send(msg);
        if (p2 != null) p2.send(msg);
    }

    private boolean isValidMove(int c) { return c >= 0 && c < cols && board[0][c] == ' '; }
    private int dropToken(int c, char s) {
        for (int r = rows - 1; r >= 0; r--) {
            if (board[r][c] == ' ') { board[r][c] = s; return r; }
        } return -1;
    }
    private boolean isBoardFull() { for(int c=0; c<cols; c++) if(board[0][c]==' ') return false; return true; }
    private boolean checkWin(int r, int c, char s) {
        return checkDir(r,c,s,1,0) || checkDir(r,c,s,0,1) || checkDir(r,c,s,1,1) || checkDir(r,c,s,1,-1);
    }
    private boolean checkDir(int r, int c, char s, int dr, int dc) {
        int cnt=0;
        for(int i=-3; i<=3; i++) {
            int nr=r+i*dr, nc=c+i*dc;
            if(nr>=0 && nr<rows && nc>=0 && nc<cols && board[nr][nc]==s) {
                cnt++; if(cnt==4) return true;
            } else cnt=0;
        } return false;
    }

    private void animateLabels() {
        dotCount = (dotCount + 1) % 4;
        String dots = ".".repeat(dotCount);
        if (p1 == null) lblP1.setText("Player 1: Scanning" + dots);
        if (p2 == null) lblP2.setText("Player 2: Scanning" + dots);
    }

    private void log(String s) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(s + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void drawBoard(Graphics g) {
        int w = boardPanel.getWidth();
        int h = boardPanel.getHeight();
        int cw = w/cols;
        int ch = h/rows;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(0, 50, 150));
        g.fillRect(0, 0, cols*cw, rows*ch);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                char s = board[r][c];
                if (s == sym1) g.setColor(Color.RED);
                else if (s == sym2) g.setColor(Color.YELLOW);
                else g.setColor(Color.WHITE);

                int padding = 5;
                int ovalX = c*cw + padding;
                int ovalY = r*ch + padding;
                int ovalW = cw - padding*2;
                int ovalH = ch - padding*2;

                g.fillOval(ovalX, ovalY, ovalW, ovalH);

                if (s != ' ') {
                    g.setColor(Color.BLACK);
                    int fontSize = Math.max(16, (int)(ch * 0.5));
                    Font font = new Font("SansSerif", Font.BOLD, fontSize);
                    g.setFont(font);

                    FontMetrics fm = g.getFontMetrics();
                    Rectangle2D bounds = fm.getStringBounds(String.valueOf(s), g);

                    int textX = ovalX + (ovalW - (int)bounds.getWidth()) / 2;
                    int textY = ovalY + (ovalH - (int)bounds.getHeight()) / 2 + fm.getAscent();

                    g.drawString(String.valueOf(s), textX, textY);
                }
            }
        }
    }

    class Player {
        Socket s;
        PrintWriter out;
        BufferedReader in;
        String name;
        char symbol;
        Player(Socket s, char sym) throws IOException {
            this.s = s;
            this.symbol = sym;
            out = new PrintWriter(s.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String n = in.readLine();
            name = (n!=null && !n.isEmpty()) ? n : "Unknown";
        }
        void send(String m) { out.println(m); }
        void close() { try { s.close(); } catch(Exception e){} }
    }
}