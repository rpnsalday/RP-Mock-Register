package main;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.swing.Timer;
import java.awt.event.KeyEvent;
import javax.swing.text.JTextComponent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;


/**
 * MockRegister is the main Swing UI for a mock point-of-sale (POS) cash register.
 * It wires together:
 * - UI components (journal, buttons, quantity editor)
 * - Keyboard/scanner input handling (fast burst detection for USB scanners)
 * - Quick item shortcuts (F1..F12 and dynamic reassignment by popularity)
 * - Transaction lifecycle (add items, hold/retrieve, cancel, finish, print)
 * - Persistence via Database (price book, transactions, held orders)
 *
 * Important navigation:
 * - initUI(): builds the layout and components.
 * - setupKeyListener(): detects and batches fast scanner input vs typing.
 * - addManualBarcode()/addItemByQuickKey(): item entry points.
 * - reassignQuickKeysByPopularity()/showPopularShortcutsInJournal(): quick key UX.
 * - updateDisplay()/refreshQtyEditorPanel(): redraws dynamic UI.
 * - finishTransaction(): saves to DB and resets state.
 */
public class MockRegister extends JFrame {
    // Fields to keep last applied discounts and computed totals for receipt/console/VJ
    private java.util.List<DiscountResult.DiscountLine> lastAppliedDiscounts = null;
    private BigDecimal lastAppliedDiscountTotal = null;
    private BigDecimal lastSubtotalUsed = null;
    private BigDecimal lastTaxUsed = null;
    private BigDecimal lastGrandTotalUsed = null;
    private JTextArea virtualJournal;
    private HashMap<String, Integer> currentTransaction;
    private BigDecimal totalAmount;
    private static final String PRICE_BOOK_FILE = "src/resources/pricebook.tsv";
    private final StringBuilder fastBurstBuffer = new StringBuilder();
    private Timer burstInactivityTimer;
    private long lastFastCharNanos = 0L;
    private static final String QUICK_KEY_ACTION_PREFIX = "quickKey.addItem.";
    private JButton payButton;
    private JButton cancelButton;
    private JButton holdButton;
    private JButton retrieveButton;
    private JButton printButton;
    private JButton viewTransactionsButton;
    private JDialog transactionsDialog;
    private JTextArea transactionsTextArea;
    private JButton subtractButton;

    private JPanel qtyPanel;
    private JScrollPane qtyScrollPane;
    private java.util.Map<String, JTextField> qtyFields = new java.util.HashMap<>();
    private boolean suppressQtyEvents = false;

    // Panel showing clickable buttons for popular shortcuts (mirrors F1..F12 assignments)
    private JPanel shortcutsPanel;
    private JScrollPane shortcutsScrollPane;

    // Panel showing buttons for all items (3 columns grid) between VJ and Qty editor
    private JPanel allItemsPanel;
    // Container with search + pagination controls (no scroll pane)
    private JPanel allItemsContainer;
    private JTextField itemsSearchField;
    private JButton itemsPrevButton;
    private JButton itemsNextButton;
    private JLabel itemsPageLabel;
    private int itemsPageIndex = 0;
    private int itemsPageSize = 24; // 3 columns x 8 rows default

    /**
     * Fast-input detection tuning for USB barcode scanners:
     * - FAST_GAP_MS: max time between characters to still consider them part of a single scan burst.
     * - INACTIVITY_COMMIT_MS: commit the buffered scan if no new chars arrive within this time.
     * - MIN/MAX_SCANNER_LEN: sanity bounds for a valid barcode length.
     */
    private static final int FAST_GAP_MS = 120;
    private static final int INACTIVITY_COMMIT_MS = 300;
    private static final int MIN_SCANNER_LEN = 2;
    private static final int MAX_SCANNER_LEN = 64;
    private static final String POPULAR_HEADER = "=".repeat(36) + " Popular Shortcuts " + "=".repeat(35);
    private static final String POPULAR_FOOTER = "=".repeat(90);
    private volatile boolean suppressDisplayUpdates = false;
    private String cachedPopularSection = null;
    private javax.swing.JTextField manualBarcodeField;
    private javax.swing.JButton manualAddButton;
    private javax.swing.JButton manualModeButton;
    private static final String TAG_KEYBOARD = "[KEYBOARD]";
    private static final String TAG_SCANGUN  = "[SCAN GUN]";
    private static final String TAG_BUTTON  = " [BUTTON] ";
    private volatile boolean manualMode = false;

    private static final ObjectMapper JSON = new ObjectMapper();
    // Endpoint for discount service, now configurable at runtime via UI
    private String discountEndpoint = System.getProperty("discount.url",
            System.getenv().getOrDefault("DISCOUNT_URL", "http://gorilla-2-47577909.ap-southeast-2.elb.amazonaws.com/discount/v1"));

    // UI controls for server config
    private JTextField hostField;
    private JTextField portField;
    private JButton setServerButton;


    public MockRegister() {
        currentTransaction = new HashMap<>();
        totalAmount = BigDecimal.ZERO;

        Database.initializeDatabase();
        Database.loadPriceBook(PRICE_BOOK_FILE);

        initUI();
        setupKeyListener();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Database.closeConnection();
            }
        });

        setupQuickItemShortcuts();
        setFullScreenSize();
    }

    /**
     * Builds the main window layout:
     * - Header with title and clock
     * - Manual barcode entry controls
     * - Left: virtual journal (receipt-like log)
     * - Right: quantity editor for cart lines
     * - Bottom: action buttons (Pay, Cancel, Hold, Retrieve, Print, History)
     */
    private void initUI() {
        setTitle("Mock Register");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        Color primaryColor = new Color(41, 128, 185); // Blue
        Color secondaryColor = new Color(52, 152, 219); // Lighter blue
        Color accentColor = new Color(231, 76, 60);  // Red for cancel/important actions
        Color backgroundColor = new Color(236, 240, 241); // Light gray background
        Color textColor = new Color(44, 62, 80); // Dark blue-gray for text

        getContentPane().setBackground(backgroundColor);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(primaryColor);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        JLabel titleLabel = new JLabel("Mock Register");
        titleLabel.setFont(new Font("Arial", Font.BOLD, 24));
        titleLabel.setForeground(Color.WHITE);
        headerPanel.add(titleLabel, BorderLayout.WEST);

        JLabel dateTimeLabel = new JLabel(
                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        );
        dateTimeLabel.setFont(new Font("Arial", Font.PLAIN, 14));
        dateTimeLabel.setForeground(Color.WHITE);
        headerPanel.add(dateTimeLabel, BorderLayout.EAST);

        JPanel manualPanel = new JPanel(new BorderLayout(6, 0));
        manualPanel.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        manualPanel.setBackground(new Color(245, 247, 249));

        JLabel manualLabel = new JLabel("Type UPC/Barcode:");
        manualLabel.setFont(new Font("Arial", Font.PLAIN, 13));
        manualLabel.setForeground(textColor);

        manualBarcodeField = new JTextField();
        manualBarcodeField.setToolTipText("Enter UPC/barcode and press Enter or click Add");
        manualBarcodeField.setColumns(18);

        manualAddButton = new JButton("Add");
        manualAddButton.setBackground(secondaryColor);
        manualAddButton.setForeground(Color.WHITE);
        manualAddButton.setFocusPainted(false);
        manualAddButton.setFont(new Font("Arial", Font.BOLD, 12));
        manualAddButton.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        manualModeButton = createStyledButton("Manual", secondaryColor, Color.WHITE);
        manualModeButton.setToolTipText("Click to manually enter a UPC/barcode");

        manualLabel.setLabelFor(manualBarcodeField);

        ActionListener manualAddAction = e -> addManualBarcode();
        manualBarcodeField.addActionListener(manualAddAction);
        manualAddButton.addActionListener(manualAddAction);

        manualModeButton.addActionListener(e -> {
            String code = JOptionPane.showInputDialog(
                    this,
                    "Enter UPC/Barcode:",
                    "Manual Entry",
                    JOptionPane.PLAIN_MESSAGE
            );
            if (code != null) {
                code = code.trim();
                if (!code.isEmpty()) {
                    addManualBarcode(code);
                }
            }
        });

        manualBarcodeField.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "clearField");
        manualBarcodeField.getActionMap().put("clearField", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                manualBarcodeField.setText("");
            }
        });

        manualBarcodeField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                manualMode = true;
                // Ensure any in-flight scanner buffer is cleared
                fastBurstBuffer.setLength(0);
                lastFastCharNanos = 0L;
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                manualMode = false;
                fastBurstBuffer.setLength(0);
                lastFastCharNanos = 0L;
            }
        });

        manualBarcodeField.addActionListener(evt -> {
            addManualBarcode();
            manualBarcodeField.requestFocusInWindow();
        });

        JPanel northContainer = new JPanel(new BorderLayout());
        northContainer.setBackground(backgroundColor);
        northContainer.add(headerPanel, BorderLayout.NORTH);

        // Server configuration panel (Host, Port, Set Server)
        JPanel serverPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        serverPanel.setBackground(new Color(245, 247, 249));
        serverPanel.setBorder(BorderFactory.createEmptyBorder(6, 15, 6, 15));

        JLabel hostLabel = new JLabel("Host:");
        hostField = new JTextField(14);
        JLabel portLabel = new JLabel("Port:");
        portField = new JTextField(5);
        setServerButton = createStyledButton("Connect Log Server", new Color(39, 174, 96), Color.WHITE);
        setServerButton.setToolTipText("Connect System.out/err to the specified log server");

        // Prefill host/port from LOG_SERVER_* configuration
        String cfgHost = System.getProperty("log.server.host");
        if (cfgHost == null || cfgHost.isBlank()) cfgHost = System.getenv("LOG_SERVER_HOST");
        if (cfgHost == null || cfgHost.isBlank()) cfgHost = "0.0.0.0";
        String cfgPortStr = System.getProperty("log.server.port");
        if (cfgPortStr == null || cfgPortStr.isBlank()) cfgPortStr = System.getenv("LOG_SERVER_PORT");
        int cfgPort = 0;
        try {
            if (cfgPortStr != null && !cfgPortStr.isBlank()) cfgPort = Integer.parseInt(cfgPortStr.trim());
        } catch (NumberFormatException ignore) { cfgPort = 5050; }
        hostField.setText(cfgHost);
        portField.setText(String.valueOf(cfgPort));

        setServerButton.addActionListener(evt -> {
            String h = hostField.getText() != null ? hostField.getText().trim() : "";
            String pStr = portField.getText() != null ? portField.getText().trim() : "";
            if (h.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Host cannot be empty.", "Invalid Host", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int p;
            try {
                p = Integer.parseInt(pStr);
                if (p < 1 || p > 65535) throw new NumberFormatException("port out of range");
            } catch (NumberFormatException nfe) {
                JOptionPane.showMessageDialog(this, "Port must be a number between 1 and 65535.", "Invalid Port", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Determine tee-local from env/props
            boolean teeLocal = false;
            String teeStr = System.getProperty("log.tee.local");
            if (teeStr == null || teeStr.isBlank()) teeStr = System.getenv("LOG_TEE_LOCAL");
            if (teeStr != null) {
                String v = teeStr.trim().toLowerCase();
                teeLocal = v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("y");
            }

            // Connect System.out/err to the specified log server
            SocketConsoleRedirector.install(h, p, teeLocal);
            JOptionPane.showMessageDialog(this, "Log server connection attempted:\n" + h + ":" + p + (teeLocal ? " (tee local)" : ""), "Log Server", JOptionPane.INFORMATION_MESSAGE);
        });

        serverPanel.add(hostLabel);
        serverPanel.add(hostField);
        serverPanel.add(portLabel);
        serverPanel.add(portField);
        serverPanel.add(setServerButton);

        northContainer.add(serverPanel, BorderLayout.CENTER);

        virtualJournal = new JTextArea();
        virtualJournal.setEditable(false);
        virtualJournal.setFocusable(false);
        virtualJournal.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        virtualJournal.setBackground(Color.WHITE);
        virtualJournal.setForeground(textColor);
        virtualJournal.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(virtualJournal);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        // Initial display
        updateDisplay();

        // Buttons panel
        JPanel buttonPanel = new JPanel(new GridLayout(2, 4, 10, 10));
        buttonPanel.setBackground(backgroundColor);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        payButton = createStyledButton("Pay", primaryColor, Color.WHITE);
        cancelButton = createStyledButton("Cancel Order", accentColor, Color.WHITE);
        holdButton = createStyledButton("Hold Order", secondaryColor, Color.WHITE);
        retrieveButton = createStyledButton("Retrieve Order", secondaryColor, Color.WHITE);
        printButton = createStyledButton("Print Receipt", secondaryColor, Color.WHITE);
        viewTransactionsButton = createStyledButton("View Transactions", secondaryColor, Color.WHITE);
        subtractButton = createStyledButton("Void Item", new Color(192, 57, 43), Color.WHITE);

        payButton.addActionListener(e -> finishTransaction());
        cancelButton.addActionListener(e -> cancelOrder());
        holdButton.addActionListener(e -> holdOrder());
        retrieveButton.addActionListener(e -> retrieveHeldOrder());
        printButton.addActionListener(e -> printReceipt());
        viewTransactionsButton.addActionListener(e -> showTransactionHistory());
        subtractButton.addActionListener(e -> voidItem());

        buttonPanel.add(payButton);
        buttonPanel.add(subtractButton);
        buttonPanel.add(cancelButton);
        buttonPanel.add(manualModeButton);
        buttonPanel.add(holdButton);
        buttonPanel.add(retrieveButton);
        buttonPanel.add(printButton);
        buttonPanel.add(viewTransactionsButton);

        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(backgroundColor);
        statusPanel.setBorder(BorderFactory.createEmptyBorder(5, 15, 10, 15));

        JLabel statusLabel = new JLabel("Ready to scan items");
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        statusLabel.setForeground(textColor);
        statusPanel.add(statusLabel, BorderLayout.WEST);

        // Main center
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(backgroundColor);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Cluster the Virtual Journal and the All-Items buttons in the center area
        JPanel centerCluster = new JPanel(new BorderLayout(8, 0));
        centerCluster.setBackground(backgroundColor);
        centerCluster.add(scrollPane, BorderLayout.CENTER); // Virtual Journal

        // All items buttons panel (3 columns) + search & pagination (no scroll)
        allItemsPanel = new JPanel(new GridLayout(0, 3, 6, 6));
        allItemsPanel.setBackground(Color.WHITE);

        // Top controls: search field and pagination
        JPanel itemsTopControls = new JPanel(new BorderLayout(6, 6));
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JLabel searchLbl = new JLabel("Search:");
        itemsSearchField = new JTextField(23);
        searchPanel.add(searchLbl);
        searchPanel.add(itemsSearchField);
        itemsTopControls.add(searchPanel, BorderLayout.WEST);

        JPanel pagerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        itemsTopControls.add(pagerPanel, BorderLayout.EAST);

        // Container with titled border
        allItemsContainer = new JPanel(new BorderLayout(6, 6));
        allItemsContainer.setBorder(BorderFactory.createTitledBorder("Items"));
        allItemsContainer.add(itemsTopControls, BorderLayout.NORTH);
        allItemsContainer.add(allItemsPanel, BorderLayout.CENTER);
        allItemsContainer.setPreferredSize(new Dimension(360, 0));
        centerCluster.add(allItemsContainer, BorderLayout.EAST);

        // Wire up search and pagination
        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            private void changed() {
                itemsPageIndex = 0;
                refreshItemsButtonPanel();
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { changed(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { changed(); }
        };
        itemsSearchField.getDocument().addDocumentListener(dl);


        mainPanel.add(centerCluster, BorderLayout.CENTER);

        // Shortcuts button panel on the left (mirrors F1..F12 popular shortcuts)
        shortcutsPanel = new JPanel(new GridLayout(0, 1, 6, 6));
        shortcutsPanel.setBackground(Color.WHITE);
        shortcutsPanel.setBorder(BorderFactory.createTitledBorder("Shortcuts"));
        shortcutsScrollPane = new JScrollPane(shortcutsPanel);
        shortcutsScrollPane.setPreferredSize(new Dimension(300, 0));
        mainPanel.add(shortcutsScrollPane, BorderLayout.WEST);

        // Quantity editor panel on the right
        qtyPanel = new JPanel(new GridBagLayout());
        qtyPanel.setBackground(Color.WHITE);
        qtyPanel.setBorder(BorderFactory.createTitledBorder("Quantities"));
        qtyScrollPane = new JScrollPane(qtyPanel);
        qtyScrollPane.setPreferredSize(new Dimension(240, 0));
        mainPanel.add(qtyScrollPane, BorderLayout.EAST);

        // Build initial quantity fields (empty)
        refreshQtyEditorPanel();

        // South area (buttons + status)
        JPanel southPanel = new JPanel(new BorderLayout());
        southPanel.setBackground(backgroundColor);
        southPanel.add(buttonPanel, BorderLayout.CENTER);
        southPanel.add(statusPanel, BorderLayout.SOUTH);

        // Assemble frame
        add(northContainer, BorderLayout.NORTH);
        add(mainPanel, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);

        setVisible(true);

        // Populate journal section for popular shortcuts and initialize shortcuts
        javax.swing.SwingUtilities.invokeLater(this::showPopularShortcutsInJournal);
        initializePopularShortcuts();
        refreshItemsButtonPanel();
    }

    private void setFullScreenSize() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation(0, 0);
        setSize(screen.width, screen.height);
        setResizable(true);
    }

    /**
     * Handles a barcode entered via keyboard/UI (not via scanner listener).
     * Validates length, looks up item, updates cart and display, with clear feedback.
     */
    private boolean addManualBarcode(String code) {
        // Stop any pending scan-burst to avoid accidental commits
        if (burstInactivityTimer != null && burstInactivityTimer.isRunning()) burstInactivityTimer.stop();
        fastBurstBuffer.setLength(0);
        lastFastCharNanos = 0L;

        if (code != null) code = code.trim();

        if (code == null || code.isEmpty()) {
            if (manualBarcodeField != null) {
                javax.swing.SwingUtilities.invokeLater(() -> manualBarcodeField.requestFocusInWindow());
            }
            return false;
        }

        if (code.length() < MIN_SCANNER_LEN || code.length() > MAX_SCANNER_LEN) {
            javax.swing.JOptionPane.showMessageDialog(
                    this,
                    "Invalid code length.",
                    "Barcode",
                    javax.swing.JOptionPane.WARNING_MESSAGE
            );
            if (manualBarcodeField != null) {
                manualBarcodeField.selectAll();
                manualBarcodeField.requestFocusInWindow();
            }
            return false;
        }

        // Directly process manual code to avoid scan-gun logging path
        Item item = Database.getItem(code);
        boolean success = item != null;
        if (success) {
            currentTransaction.merge(code, 1, Integer::sum);
            totalAmount = calculateTotal();
            updateDisplay();
            // Console log to clearly mark manual keyboard input
            System.out.println("\n" + TAG_KEYBOARD + ": " + code);
            System.out.println(TAG_KEYBOARD + " Item found: " + item.description + " - $" + item.price);
            if (manualBarcodeField != null) {
                manualBarcodeField.setText("");
                javax.swing.SwingUtilities.invokeLater(() -> manualBarcodeField.requestFocusInWindow());
            }
            return true;
        } else {
            // Provide clear feedback and keep the code so the user can fix it
            Toolkit.getDefaultToolkit().beep();
            javax.swing.JOptionPane.showMessageDialog(
                    this,
                    "Item not found for code: " + code,
                    "Not Found",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE
            );
            if (manualBarcodeField != null) {
                manualBarcodeField.selectAll();
                manualBarcodeField.requestFocusInWindow();
            }
            return false;
        }
    }

    private boolean addManualBarcode() {
        if (manualBarcodeField == null) return false;
        return addManualBarcode(manualBarcodeField.getText());
    }

    private void initializePopularShortcuts() {
        refreshPopularityFromDb();
        reassignQuickKeysByPopularity();
        showPopularShortcutsInJournal();
        refreshShortcutsButtonPanel();
    }


    private final java.util.Map<String, java.util.List<javax.swing.KeyStroke>> quickKeysByUpc = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> popularityCounts = new java.util.HashMap<>();
    private static final javax.swing.KeyStroke[] FUNCTION_KEYS = new javax.swing.KeyStroke[] {
            javax.swing.KeyStroke.getKeyStroke("F1"),
            javax.swing.KeyStroke.getKeyStroke("F2"),
            javax.swing.KeyStroke.getKeyStroke("F3"),
            javax.swing.KeyStroke.getKeyStroke("F4"),
            javax.swing.KeyStroke.getKeyStroke("F5"),
            javax.swing.KeyStroke.getKeyStroke("F6"),
            javax.swing.KeyStroke.getKeyStroke("F7"),
            javax.swing.KeyStroke.getKeyStroke("F8"),
            javax.swing.KeyStroke.getKeyStroke("F9"),
            javax.swing.KeyStroke.getKeyStroke("F10"),
            javax.swing.KeyStroke.getKeyStroke("F11"),
            javax.swing.KeyStroke.getKeyStroke("F12")
    };

    /**
     * Rebuilds F1..F12 quick item bindings based on transaction popularity.
     * Stable sorting: popularity desc, then description, then UPC to avoid jitter.
     * Updates InputMap/ActionMap and mirrors assignments into quickKeysByUpc for journal rendering.
     */
    private void reassignQuickKeysByPopularity() {
        javax.swing.JRootPane root = getRootPane();
        if (root == null) return;

        // Rank UPCs by popularity (desc), tie-break by description then UPC for stability
        java.util.List<java.util.Map.Entry<String, Integer>> ranked = new java.util.ArrayList<>(popularityCounts.entrySet());
        ranked.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            Item ia = Database.getItem(a.getKey());
            Item ib = Database.getItem(b.getKey());
            String da = ia != null ? ia.description : "";
            String db = ib != null ? ib.description : "";
            cmp = da.compareToIgnoreCase(db);
            if (cmp != 0) return cmp;
            return a.getKey().compareTo(b.getKey());
        });

        // Prepare input/action maps
        javax.swing.JComponent comp = root;
        int condition = javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW;
        javax.swing.InputMap im = comp.getInputMap(condition);
        javax.swing.ActionMap am = comp.getActionMap();

        if (im == null || am == null) return;

        // Clear previous function-key bindings
        for (javax.swing.KeyStroke ks : FUNCTION_KEYS) {
            Object bound = im.get(ks);
            if (bound != null) {
                im.remove(ks);
            }
        }

        // Rebuild quickKeysByUpc for visible F-key assignments
        quickKeysByUpc.clear();

        int limit = Math.min(FUNCTION_KEYS.length, ranked.size());
        for (int i = 0; i < limit; i++) {
            String upc = ranked.get(i).getKey();
            javax.swing.KeyStroke ks = FUNCTION_KEYS[i];
            String actionKey = QUICK_KEY_ACTION_PREFIX + upc;

            javax.swing.Action act = am.get(actionKey);
            if (act == null) {
                act = new javax.swing.AbstractAction(actionKey) {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        addItemByQuickKey(upc);
                    }
                };
                am.put(actionKey, act);
            }

            im.put(ks, actionKey);

            quickKeysByUpc.put(upc, java.util.List.of(ks));
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            showPopularShortcutsInJournal();
            refreshShortcutsButtonPanel();
        });
    }

    /**
     * Initial (on startup) quick item bindings from historical top sellers.
     * This seeds F1..F12 before any new popularity data is collected in-session.
     * Also triggers the journal section to show current shortcut assignments.
     */
    private void setupQuickItemShortcuts() {
        quickKeysByUpc.clear();

        java.util.List<String> topUPCs = Database.getTopSellingUPCs(12);
        if (topUPCs == null || topUPCs.isEmpty() || getRootPane() == null) {
            // Still refresh the journal and shortcuts panel so it shows “no shortcut” state cleanly
            javax.swing.SwingUtilities.invokeLater(() -> {
                showPopularShortcutsInJournal();
                refreshShortcutsButtonPanel();
            });
            return;
        }

        String[] functionKeys = new String[]{"F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12"};
        int count = Math.min(functionKeys.length, topUPCs.size());

        javax.swing.JRootPane root = getRootPane();
        javax.swing.InputMap im = root.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = root.getActionMap();

        // Cleanup previous bindings for all function keys to avoid duplicates
        for (String fk : functionKeys) {
            javax.swing.KeyStroke ks = javax.swing.KeyStroke.getKeyStroke(fk);
            if (ks != null) {
                String oldAction = QUICK_KEY_ACTION_PREFIX + fk;
                Object mapped = im.get(ks);
                if (mapped != null) im.remove(ks);
                if (am.get(oldAction) != null) am.remove(oldAction);
            }
        }

        // Bind new ones and record UPC -> KeyStrokes
        for (int i = 0; i < count; i++) {
            String fk = functionKeys[i];
            String upc = topUPCs.get(i);
            javax.swing.KeyStroke ks = javax.swing.KeyStroke.getKeyStroke(fk);
            if (ks == null) continue;

            String actionName = QUICK_KEY_ACTION_PREFIX + fk;
            im.put(ks, actionName);
            am.put(actionName, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    addItemByQuickKey(upc);
                }
            });

            quickKeysByUpc.computeIfAbsent(upc, __ -> new java.util.ArrayList<>()).add(ks);
        }

        javax.swing.SwingUtilities.invokeLater(() -> {
            showPopularShortcutsInJournal();
            refreshShortcutsButtonPanel();
        });
    }

    private void collectAllComponents(java.awt.Component root, java.util.List<java.awt.Component> out) {
        if (root == null) return;
        out.add(root);
        if (root instanceof java.awt.Container cont) {
            for (java.awt.Component child : cont.getComponents()) {
                collectAllComponents(child, out);
            }
        }
    }
    /**
     * Renders a human-readable section at the top of the virtual journal that lists
     * the most popular quick shortcuts. It prefixes each item with its primary F-key
     * label (e.g., [F1]) when available and keeps the section cached for stable UI updates.
     */
    private void showPopularShortcutsInJournal() {
        // Build the popular shortcuts section text
        StringBuilder psb = new StringBuilder();
        psb.append(POPULAR_HEADER).append("\n");

        if (quickKeysByUpc == null || quickKeysByUpc.isEmpty()) {
            psb.append("No shortcuts configured.\n");
        } else {
            java.util.function.ToIntFunction<javax.swing.KeyStroke> fIndex = ks -> {
                for (int i = 0; i < FUNCTION_KEYS.length; i++) {
                    if (FUNCTION_KEYS[i].equals(ks)) return i;
                }
                return 999; // non-function keys go to the end, just in case
            };

            // Collect entries with their primary F-key index (the first/lowest index among assigned strokes)
            java.util.List<java.util.Map.Entry<String, java.util.List<javax.swing.KeyStroke>>> entries =
                    new java.util.ArrayList<>(quickKeysByUpc.entrySet());

            entries.sort((e1, e2) -> {
                // Determine primary index for each entry
                int i1 = 999, i2 = 999;
                if (e1.getValue() != null && !e1.getValue().isEmpty()) {
                    for (javax.swing.KeyStroke ks : e1.getValue()) i1 = Math.min(i1, fIndex.applyAsInt(ks));
                }
                if (e2.getValue() != null && !e2.getValue().isEmpty()) {
                    for (javax.swing.KeyStroke ks : e2.getValue()) i2 = Math.min(i2, fIndex.applyAsInt(ks));
                }
                int cmp = Integer.compare(i1, i2);
                if (cmp != 0) return cmp;

                // Stable tie-breaker by description then UPC
                String upc1 = e1.getKey();
                String upc2 = e2.getKey();
                Item iA = Database.getItem(upc1);
                Item iB = Database.getItem(upc2);
                String dA = iA != null ? iA.description : "";
                String dB = iB != null ? iB.description : "";
                cmp = dA.compareToIgnoreCase(dB);
                if (cmp != 0) return cmp;
                return upc1.compareTo(upc2);
            });

            for (var entry : entries) {
                String upc = entry.getKey();

                // Resolve description
                Item item = Database.getItem(upc);
                String desc = (item != null) ? truncateString(item.description, 50) : "(Unknown Item)";

                // Format keystrokes and detect primary F-key label
                java.util.List<javax.swing.KeyStroke> strokes = entry.getValue();
                StringBuilder keysSb = new StringBuilder();
                String primaryFLabel = null;
                if (strokes != null && !strokes.isEmpty()) {
                    int bestIdx = 999;
                    for (int i = 0; i < strokes.size(); i++) {
                        javax.swing.KeyStroke ks = strokes.get(i);
                        if (i > 0) keysSb.append(", ");
                        keysSb.append(formatKeyStroke(ks));

                        int idx = -1;
                        for (int j = 0; j < FUNCTION_KEYS.length; j++) {
                            if (FUNCTION_KEYS[j].equals(ks)) { idx = j; break; }
                        }
                        if (idx >= 0 && idx < bestIdx) {
                            bestIdx = idx;
                            primaryFLabel = "F" + (idx + 1);
                        }
                    }
                } else {
                    keysSb.append(upc);
                }

                // Prefix entry with primary F-key label for clarity
                if (primaryFLabel != null) {
                    psb.append("[").append(primaryFLabel).append("] ");
                } else {
                    psb.append("    "); // align non-F-key entries if any
                }

                psb.append("• ").append(desc).append("\n");
            }
        }

        psb.append(POPULAR_FOOTER).append("\n\n");
        String newSection = psb.toString();

        // Cache for static reuse during the transaction
        cachedPopularSection = newSection;

        // Merge into the current journal text
        if (virtualJournal != null) {
            String current = virtualJournal.getText();
            String merged = replacePopularSection(current, cachedPopularSection);
            virtualJournal.setText(merged);
            virtualJournal.setCaretPosition(virtualJournal.getDocument().getLength());
        }
    }

    private void refreshShortcutsButtonPanel() {
        if (shortcutsPanel == null) return;
        shortcutsPanel.removeAll();

        java.util.List<java.util.Map.Entry<String, java.util.List<javax.swing.KeyStroke>>> entries =
                (quickKeysByUpc == null) ? java.util.List.of() : new java.util.ArrayList<>(quickKeysByUpc.entrySet());

        java.util.function.ToIntFunction<javax.swing.KeyStroke> fIndex = ks -> {
            for (int i = 0; i < FUNCTION_KEYS.length; i++) {
                if (FUNCTION_KEYS[i].equals(ks)) return i;
            }
            return 999;
        };

        entries.sort((e1, e2) -> {
            int i1 = 999, i2 = 999;
            if (e1.getValue() != null && !e1.getValue().isEmpty()) {
                for (javax.swing.KeyStroke ks : e1.getValue()) i1 = Math.min(i1, fIndex.applyAsInt(ks));
            }
            if (e2.getValue() != null && !e2.getValue().isEmpty()) {
                for (javax.swing.KeyStroke ks : e2.getValue()) i2 = Math.min(i2, fIndex.applyAsInt(ks));
            }
            int cmp = Integer.compare(i1, i2);
            if (cmp != 0) return cmp;

            String upc1 = e1.getKey();
            String upc2 = e2.getKey();
            Item iA = Database.getItem(upc1);
            Item iB = Database.getItem(upc2);
            String dA = iA != null ? iA.description : "";
            String dB = iB != null ? iB.description : "";
            cmp = dA.compareToIgnoreCase(dB);
            if (cmp != 0) return cmp;
            return upc1.compareTo(upc2);
        });

        if (entries.isEmpty()) {
            shortcutsPanel.setLayout(new BorderLayout());
            JLabel lbl = new JLabel("No shortcuts configured.");
            lbl.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            shortcutsPanel.add(lbl, BorderLayout.NORTH);
        } else {
            shortcutsPanel.setLayout(new GridLayout(0, 1, 6, 6));
            for (var entry : entries) {
                String upc = entry.getKey();
                Item item = Database.getItem(upc);
                String desc = (item != null) ? truncateString(item.description, 32) : "(Unknown Item)";

                String primaryFLabel = null;
                java.util.List<javax.swing.KeyStroke> strokes = entry.getValue();
                if (strokes != null && !strokes.isEmpty()) {
                    int bestIdx = 999;
                    for (javax.swing.KeyStroke ks : strokes) {
                        for (int j = 0; j < FUNCTION_KEYS.length; j++) {
                            if (FUNCTION_KEYS[j].equals(ks)) {
                                if (j < bestIdx) {
                                    bestIdx = j;
                                    primaryFLabel = "F" + (j + 1);
                                }
                            }
                        }
                    }
                }

                String btnText = (primaryFLabel != null ? "[" + primaryFLabel + "] " : "") + desc;
                JButton btn = createStyledButton(btnText, new Color(52, 152, 219), Color.WHITE);
                btn.setHorizontalAlignment(SwingConstants.LEFT);
                btn.setToolTipText(((item != null) ? item.description : upc) + " (" + upc + ")");
                btn.addActionListener(e -> addItemByQuickKey(upc));
                shortcutsPanel.add(btn);
            }
        }

        shortcutsPanel.revalidate();
        shortcutsPanel.repaint();
    }

    // Builds/refreshes the grid of item buttons (3 columns) using all items from DB
    private void refreshItemsButtonPanel() {
        if (allItemsPanel == null) return;
        allItemsPanel.removeAll();

        java.util.List<Item> items = Database.getAllItems();
        if (items == null) items = java.util.List.of();

        // Apply search filter (by description or UPC)
        String q = (itemsSearchField != null && itemsSearchField.getText() != null)
                ? itemsSearchField.getText().trim().toLowerCase()
                : "";
        if (!q.isEmpty()) {
            java.util.List<Item> filtered = new java.util.ArrayList<>();
            for (Item it : items) {
                String d = it.description != null ? it.description.toLowerCase() : "";
                String u = it.upc != null ? it.upc.toLowerCase() : "";
                if (d.contains(q) || u.contains(q)) filtered.add(it);
            }
            items = filtered;
        }

        int totalItems = items.size();
        if (totalItems == 0) {
            allItemsPanel.setLayout(new BorderLayout());
            JLabel lbl = new JLabel("No items available.");
            lbl.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            allItemsPanel.add(lbl, BorderLayout.NORTH);
            if (itemsPageLabel != null) itemsPageLabel.setText("Page 0 of 0");
            if (itemsPrevButton != null) itemsPrevButton.setEnabled(false);
            if (itemsNextButton != null) itemsNextButton.setEnabled(false);
        } else {
            // Limit to 7 rows maximum (21 buttons per page)
            int maxButtonsPerPage = 21;
            if (itemsPageSize > maxButtonsPerPage) itemsPageSize = maxButtonsPerPage;

            // Recalculate pagination with new page size limit
            int totalPages = (int) Math.ceil(totalItems / (double) itemsPageSize);
            if (itemsPageIndex >= totalPages) itemsPageIndex = Math.max(0, totalPages - 1);
            int start = itemsPageIndex * itemsPageSize;
            int end = Math.min(start + itemsPageSize, totalItems);

            // Use GridLayout with maximum 7 rows
            allItemsPanel.setLayout(new GridLayout(7, 3, 6, 6));

            for (int i = start; i < end; i++) {
                Item item = items.get(i);
                String text = item.description != null ? item.description : "";

                // Create properly wrapped text for button
                String wrappedText = wrapTextForButton(text, 12); // 12 chars per line approximately

                JButton btn = createStyledButton(wrappedText, new Color(149, 165, 166), Color.WHITE);

                // Set fixed button size
                btn.setPreferredSize(new Dimension(120, 80));
                btn.setMinimumSize(new Dimension(120, 80));
                btn.setMaximumSize(new Dimension(120, 80));

                btn.setToolTipText(item.description + " (" + item.upc + ")");
                String upc = item.upc;
                btn.addActionListener(e -> addItemByButton(upc));
                allItemsPanel.add(btn);
            }

            // Fill remaining grid spaces with invisible panels to maintain layout
            int buttonsAdded = end - start;
            int remainingSpaces = maxButtonsPerPage - buttonsAdded;
            for (int i = 0; i < remainingSpaces; i++) {
                JPanel filler = new JPanel();
                filler.setVisible(false);
                allItemsPanel.add(filler);
            }

            // Update pagination controls
            if (itemsPageLabel != null) {
                itemsPageLabel.setText("Page " + (itemsPageIndex + 1) + " of " + totalPages);
            }
            if (itemsPrevButton != null) {
                itemsPrevButton.setEnabled(itemsPageIndex > 0);
            }
            if (itemsNextButton != null) {
                itemsNextButton.setEnabled(itemsPageIndex < totalPages - 1);
            }
        }
        allItemsPanel.revalidate();
        allItemsPanel.repaint();
    }

    // Helper method to wrap text properly for buttons
    private String wrapTextForButton(String text, int maxCharsPerLine) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        text = text.trim();
        if (text.length() <= maxCharsPerLine) {
            return "<html><center>" + text + "</center></html>";
        }

        StringBuilder wrapped = new StringBuilder("<html><center>");
        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.length() + word.length() + 1 <= maxCharsPerLine) {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            } else {
                if (currentLine.length() > 0) {
                    wrapped.append(currentLine.toString()).append("<br>");
                    currentLine = new StringBuilder(word);
                } else {
                    // Single word longer than max chars - truncate it
                    wrapped.append(word.substring(0, Math.min(word.length(), maxCharsPerLine - 3))).append("...<br>");
                }
            }
        }

        if (currentLine.length() > 0) {
            wrapped.append(currentLine.toString());
        }

        wrapped.append("</center></html>");
        return wrapped.toString();
    }

    // Adds item to cart specifically via button press and logs with TAG_BUTTON
    private void addItemByButton(String upc) {
        if (upc == null || upc.isBlank()) return;
        Item item = Database.getItem(upc);
        if (item == null) {
            Toolkit.getDefaultToolkit().beep();
            System.out.println(TAG_BUTTON + " ERROR: Item not found for UPC: " + upc);
            return;
        }
        currentTransaction.merge(upc, 1, Integer::sum);
        totalAmount = calculateTotal();
        updateDisplay(true);
        System.out.println("\n" + TAG_BUTTON + ": " + upc);
        System.out.println(TAG_BUTTON + " Item added: " + item.description + " - $" + item.price);
    }

    private String replacePopularSection(String text, String newSection) {
        int start = text.indexOf(POPULAR_HEADER);
        if (start == -1) {
            // No section yet: prepend
            return newSection + text;
        }
        int footerIdx = text.indexOf(POPULAR_FOOTER, start);
        if (footerIdx == -1) {
            // Header without footer: just rebuild from header to end-of-section best effort
            return newSection + text.substring(0, start) + text.substring(start).replaceFirst("(?s)^.*$", "");
        }
        // Include footer line and trailing newlines after it
        int end = footerIdx + POPULAR_FOOTER.length();
        // Optionally consume trailing newlines
        while (end < text.length() && (text.charAt(end) == '\n' || text.charAt(end) == '\r')) end++;
        return text.substring(0, start) + newSection + text.substring(end);
    }

    private String formatKeyStroke(javax.swing.KeyStroke ks) {
        if (ks == null) return "";
        String modText = InputEvent.getModifiersExText(ks.getModifiers());
        String keyText = (ks.getKeyChar() != KeyEvent.CHAR_UNDEFINED)
                ? String.valueOf(ks.getKeyChar()).toUpperCase()
                : KeyEvent.getKeyText(ks.getKeyCode());
        if (modText.isBlank()) return keyText;
        return modText + "+" + keyText;
    }

    private java.util.List<javax.swing.KeyStroke> findKeyStrokesForAction(String actionKey) {
        java.util.List<javax.swing.KeyStroke> result = new java.util.ArrayList<>();
        if (getRootPane() == null) return result;

        // Gather components to inspect (root pane and all descendants)
        java.util.List<java.awt.Component> components = new java.util.ArrayList<>();
        collectAllComponents(getRootPane(), components);

        int[] conditions = new int[] {
                javax.swing.JComponent.WHEN_FOCUSED,
                javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
                javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW
        };

        for (java.awt.Component c : components) {
            if (!(c instanceof javax.swing.JComponent jc)) continue;

            javax.swing.ActionMap am = jc.getActionMap();
            for (int cond : conditions) {
                javax.swing.InputMap im = jc.getInputMap(cond);
                if (im == null) continue;
                Object[] keys = im.allKeys();
                if (keys == null) continue;

                for (Object k : keys) {
                    if (!(k instanceof javax.swing.KeyStroke ks)) continue;

                    Object bound = im.get(ks);
                    if (bound == null) continue;

                    // Case 1: value is a String action key
                    if (bound instanceof String s && s.equals(actionKey)) {
                        result.add(ks);
                        continue;
                    }

                    // Case 2: value is a non-string key; resolve via ActionMap and check Action.NAME
                    if (am != null) {
                        javax.swing.Action act = am.get(bound);
                        if (act != null) {
                            Object name = act.getValue(javax.swing.Action.NAME);
                            if (name != null && actionKey.equals(name.toString())) {
                                result.add(ks);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }


    private void addItemByQuickKey(String code) {
        if (hasRecentFastInput(System.nanoTime())) {
            suspendDisplayUpdates();
        }

        long now = System.nanoTime();
        clearIfStale(now);

        fastBurstBuffer.setLength(0);
        fastBurstBuffer.append(code);
        lastFastCharNanos = now;

        commitFastBurstIfValid();
        if (!suppressDisplayUpdates) {
            updateDisplay();
        }
    }

    private JButton createStyledButton(String text, Color bgColor, Color fgColor) {
        JButton button = new JButton(text);
        button.setBackground(bgColor);
        button.setForeground(fgColor);
        button.setFont(new Font("Arial", Font.BOLD, 14));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBorderPainted(true);
        button.setContentAreaFilled(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(bgColor.darker(), 1),
                BorderFactory.createEmptyBorder(8, 15, 8, 15)
        ));

        // Add hover effect
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(bgColor.darker());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(bgColor);
            }
        });

        return button;
    }

    /**
     * Global AWT key listener that distinguishes between human typing and USB barcode scanner input.
     * Strategy:
     * - Ignore all key events when a text component is focused (to avoid hijacking manual input).
     * - Accumulate only alphanumeric printable chars into a short-lived buffer.
     * - If inter-key gap <= FAST_GAP_MS, we treat it as part of the same "scan burst".
     * - Commit the buffered UPC when Enter arrives during a burst or after INACTIVITY_COMMIT_MS of silence.
     */
    private void setupKeyListener() {
        burstInactivityTimer = new Timer(INACTIVITY_COMMIT_MS, e -> commitFastBurstIfValid());
        burstInactivityTimer.setRepeats(false);

        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (!(event instanceof KeyEvent)) return;
                KeyEvent ke = (KeyEvent) event;
                if (ke.getID() != KeyEvent.KEY_TYPED) return;

                // Identify context and source
                Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                Object src = ke.getSource();

                // HARD GUARD: do nothing if the manual field is focused or event originates from a text field
                if ((manualBarcodeField != null && manualBarcodeField.isFocusOwner())
                        || (src instanceof JTextComponent)
                        || (focusOwner instanceof JTextComponent)) {
                    if (burstInactivityTimer.isRunning()) burstInactivityTimer.stop();
                    fastBurstBuffer.setLength(0);
                    lastFastCharNanos = 0L;
                    return;
                }

                char ch = ke.getKeyChar();
                boolean isPrintable = ch != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(ch);
                boolean isEnter = (ch == '\n' || ch == '\r');
                long now = System.nanoTime();

                if (isEnter) {
                    // Never commit a burst from Enter if coming from any text component context
                    if (src instanceof JTextComponent || focusOwner instanceof JTextComponent) {
                        if (burstInactivityTimer.isRunning()) burstInactivityTimer.stop();
                        fastBurstBuffer.setLength(0);
                        lastFastCharNanos = 0L;
                        return;
                    }
                    if (hasRecentFastInput(now)) {
                        commitFastBurstIfValid();
                    } else {
                        clearIfStale(now);
                    }
                    return;
                }

                if (!isPrintable || !Character.isLetterOrDigit(ch)) {
                    clearIfStale(now);
                    return;
                }

                boolean continuesFastBurst = lastFastCharNanos > 0 &&
                        ((now - lastFastCharNanos) / 1_000_000L) <= FAST_GAP_MS;

                if (!continuesFastBurst) {
                    commitFastBurstIfValid(); // safe no-op if buffer empty/invalid
                    fastBurstBuffer.setLength(0);
                }

                if (fastBurstBuffer.length() < MAX_SCANNER_LEN) {
                    fastBurstBuffer.append(ch);
                }
                lastFastCharNanos = now;
                burstInactivityTimer.restart();
            }
        }, AWTEvent.KEY_EVENT_MASK);
    }

    private boolean hasRecentFastInput(long nowNanos) {
        if (lastFastCharNanos == 0) return false;
        long gapMs = (nowNanos - lastFastCharNanos) / 1_000_000L;
        return gapMs <= INACTIVITY_COMMIT_MS;
    }

    private void clearIfStale(long nowNanos) {
        if (!hasRecentFastInput(nowNanos)) {
            fastBurstBuffer.setLength(0);
            lastFastCharNanos = 0L;
            burstInactivityTimer.stop();
        }
    }

    /**
     * Commits the buffered scanner input if it looks like a valid barcode.
     * Drops the buffer if too short or if manual entry is focused.
     * Resolves the item, updates the cart, and refreshes the UI.
     */
    private void commitFastBurstIfValid() {
        if (manualBarcodeField != null && manualBarcodeField.isFocusOwner()) {
            fastBurstBuffer.setLength(0);
            lastFastCharNanos = 0L;
            return;
        }

        if (fastBurstBuffer.length() >= MIN_SCANNER_LEN) {
            String upc = fastBurstBuffer.toString().trim();
            fastBurstBuffer.setLength(0);
            lastFastCharNanos = 0L;

            System.out.println("\n" + TAG_SCANGUN + ": " + upc);

            Item item = Database.getItem(upc);
            if (item != null) {
                System.out.println(TAG_SCANGUN + " Item found: " + item.description + " - $" + item.price);
                currentTransaction.merge(upc, 1, Integer::sum);
                totalAmount = calculateTotal();
                updateDisplay();
            } else {
                System.out.println(TAG_SCANGUN + " ERROR: Item not found for UPC: " + upc);
                virtualJournal.append(String.format("\n\nUPC: %s\tItem not found.\n\n", upc));
            }
        } else {
            // Not enough fast chars to be a scanner: drop it
            fastBurstBuffer.setLength(0);
            lastFastCharNanos = 0L;
        }
        burstInactivityTimer.stop();
        resumeDisplayUpdatesAndRefresh();
    }

    private void suspendDisplayUpdates() { suppressDisplayUpdates = true; }
    private void resumeDisplayUpdatesAndRefresh() {
        suppressDisplayUpdates = false;
        updateDisplay(true); // force one final refresh
    }


    private void updateDisplay() {
        updateDisplay(false);
    }

    private void updateDisplay(boolean force) {
        if (suppressDisplayUpdates && !force) {
            return;
        }

        BigDecimal subtotal = calculateTotal().setScale(2, RoundingMode.HALF_UP);
        totalAmount = subtotal; // keep field in sync
        BigDecimal tax = calculateTax(subtotal);
        BigDecimal grandTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxRatePct = calculateTax(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        StringBuilder sb = new StringBuilder();
        sb.append("MOCK REGISTER STORE\n");
        sb.append("Transaction: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                .append("\n\n");

        // Header
        sb.append(String.format("%-8s %-50s %-15s %-15s\n", "QTY", "ITEM DESCRIPTION", "PRICE", "SUBTOTAL"));
        sb.append("=".repeat(90)).append("\n");

        if (currentTransaction.isEmpty()) {
            sb.append("\n\n");
            sb.append("          Cart is empty. Scan items to add them to your transaction.\n");
            sb.append("\n\n");
        } else {
            for (var entry : currentTransaction.entrySet()) {
                Item item = Database.getItem(entry.getKey());
                if (item != null) {
                    BigDecimal lineSubtotal = item.price.multiply(new BigDecimal(entry.getValue()))
                            .setScale(2, RoundingMode.HALF_UP);
                    sb.append(String.format("%-8d %-50s $%-14.2f $%-14.2f\n",
                            entry.getValue(),
                            truncateString(item.description, 50),
                            item.price.doubleValue(),
                            lineSubtotal.doubleValue()));
                }
            }
        }

        // Totals
        sb.append("\n").append("-".repeat(90)).append("\n");
        sb.append(String.format("%76s $%-12.2f\n", "SUBTOTAL:", subtotal.doubleValue()));
        sb.append(String.format("%70s (%d%%): $%-12.2f\n", "TAX", taxRatePct.intValue(), tax.doubleValue()));
        sb.append(String.format("%76s $%-12.2f\n", "TOTAL:", grandTotal.doubleValue()));
        sb.append("\n\n");
        sb.append("Scan items to add to cart. Use buttons below to complete transaction.");

        if (virtualJournal != null) {
            String display = sb.toString();
            // Merge the cached popular section so it stays visible and static
            if (cachedPopularSection != null) {
                display = replacePopularSection(display, cachedPopularSection);
            }
            virtualJournal.setText(display);
            virtualJournal.setCaretPosition(virtualJournal.getDocument().getLength());
        }

        // Refresh the quantity editor to reflect current items and amounts
        refreshQtyEditorPanel();

        boolean hasItems = subtotal.compareTo(BigDecimal.ZERO) > 0;

        if (payButton != null) payButton.setEnabled(hasItems);
        if (cancelButton != null) cancelButton.setEnabled(hasItems);
        if (holdButton != null) holdButton.setEnabled(hasItems);
        if (printButton != null) printButton.setEnabled(hasItems);
        if (subtractButton != null) subtractButton.setEnabled(hasItems);
        if (retrieveButton != null) retrieveButton.setEnabled(true);
        if (viewTransactionsButton != null) viewTransactionsButton.setEnabled(true);
    }

    // Build or refresh the right-side quantity editor panel
    private void refreshQtyEditorPanel() {
        if (qtyPanel == null) return;
        suppressQtyEvents = true;
        qtyFields.clear();
        qtyPanel.removeAll();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Header row
        JLabel hdrItem = new JLabel("Item");
        hdrItem.setFont(hdrItem.getFont().deriveFont(Font.BOLD));
        qtyPanel.add(hdrItem, gbc);
        gbc.gridx = 1;
        JLabel hdrQty = new JLabel("Qty");
        hdrQty.setFont(hdrQty.getFont().deriveFont(Font.BOLD));
        qtyPanel.add(hdrQty, gbc);

        // Collect and sort items for stable order (by description then UPC)
        java.util.List<String> upcs = new java.util.ArrayList<>(currentTransaction.keySet());
        upcs.sort((a, b) -> {
            Item ia = Database.getItem(a);
            Item ib = Database.getItem(b);
            String da = ia != null && ia.description != null ? ia.description : "";
            String db = ib != null && ib.description != null ? ib.description : "";
            int cmp = da.compareToIgnoreCase(db);
            if (cmp != 0) return cmp;
            return a.compareTo(b);
        });

        for (String upc : upcs) {
            Integer qty = currentTransaction.get(upc);
            Item item = Database.getItem(upc);
            String label = item != null ? truncateString(item.description, 22) : upc;

            gbc.gridy++;
            gbc.gridx = 0;
            JLabel itemLbl = new JLabel(label);
            qtyPanel.add(itemLbl, gbc);

            gbc.gridx = 1;
            JTextField tf = new JTextField(String.valueOf(qty != null ? qty : 0), 4);
            tf.putClientProperty("upc", upc);
            tf.setHorizontalAlignment(SwingConstants.RIGHT);
            // On Enter, apply value
            tf.addActionListener(e -> applyQtyChange((String) tf.getClientProperty("upc"), tf.getText()));

            qtyFields.put(upc, tf);
            qtyPanel.add(tf, gbc);
        }

        // Filler to push components to top
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        qtyPanel.add(Box.createVerticalGlue(), gbc);

        qtyPanel.revalidate();
        qtyPanel.repaint();
        suppressQtyEvents = false;
    }

    private void applyQtyChange(String upc, String text) {
        if (suppressQtyEvents) return;
        if (upc == null) return;
        text = text != null ? text.trim() : "";
        int newQty;
        try {
            newQty = Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            // Revert the field to the current value
            Integer cur = currentTransaction.get(upc);
            JTextField tf = qtyFields.get(upc);
            if (tf != null) tf.setText(String.valueOf(cur != null ? cur : 0));
            Toolkit.getDefaultToolkit().beep();
            return;
        }

        if (newQty <= 0) {
            currentTransaction.remove(upc);
            System.out.println("Item [" + upc + "] has been voided");

        } else {
            currentTransaction.put(upc, newQty);
            System.out.println("Quantity for Item [" + upc + "] has been changed to " + newQty);
        }
        totalAmount = calculateTotal();
        updateDisplay();
    }

    private String truncateString(String input, int maxLength) {
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength - 3) + "...";
    }

    /**
     * Computes the untaxed subtotal by summing price * quantity for items in the cart.
     * Uses Database.getItem(upc) to resolve current price from the price book.
     */
    private BigDecimal calculateTotal() {
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : currentTransaction.entrySet()) {
            Item item = Database.getItem(entry.getKey());
            if (item != null) {
                total = total.add(item.price.multiply(new BigDecimal(entry.getValue())));
            }
        }
        return total;
    }

    private void cancelOrder() {
        if (currentTransaction.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No active transaction to cancel.",
                    "Cancel Order",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int result = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to cancel this order?",
                "Cancel Order",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            // Compute amounts for console logging before clearing
            BigDecimal subtotal = calculateTotal().setScale(2, RoundingMode.HALF_UP);
            BigDecimal tax = calculateTax(subtotal);
            BigDecimal grandTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
            BigDecimal taxRatePct = calculateTax(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

            System.out.println("\n=== TRANSACTION CANCELED ===");

            // Print detailed items that were canceled
            System.out.println("Items canceled:");
            for (var entry : currentTransaction.entrySet()) {
                String upc = entry.getKey();
                int qty = entry.getValue();

                Item item = Database.getItem(upc);
                String desc = (item != null && item.getDescription() != null) ? item.getDescription() : upc;
                BigDecimal unitPrice = (item != null && item.getPrice() != null) ? item.getPrice() : BigDecimal.ZERO;
                BigDecimal lineSubtotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

                System.out.printf("  %d x %s @ $%.2f = $%.2f%n",
                        qty,
                        truncateString(desc, 30),
                        unitPrice.doubleValue(),
                        lineSubtotal.doubleValue());
            }

            System.out.printf("Items in cart: %d%n", currentTransaction.size());
            System.out.printf("Subtotal: $%.2f%n", subtotal.doubleValue());
            System.out.printf("Tax (%.2f%%): $%.2f%n", taxRatePct.doubleValue(), tax.doubleValue());
            System.out.printf("Total: $%.2f%n", grandTotal.doubleValue());
            System.out.println("============================\n");

            currentTransaction.clear();
            totalAmount = BigDecimal.ZERO;
            updateDisplay();
        }
    }

    private void holdOrder() {
        if (currentTransaction.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No active transaction to hold.",
                    "Hold Order",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        long holdId = Database.holdOrder(currentTransaction, totalAmount);
        if (holdId != -1) {
            currentTransaction.clear();
            totalAmount = BigDecimal.ZERO;
            updateDisplay();
            Database.printAllTables();  // Add this line

            JOptionPane.showMessageDialog(this,
                    "Order held successfully. Order ID: " + holdId,
                    "Order Held",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void retrieveHeldOrder() {
        java.util.List<Database.HeldOrderInfo> held = Database.listHeldOrders();
        if (held.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "There are no held orders to retrieve.",
                    "Retrieve Held Order",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JComboBox<Database.HeldOrderInfo> combo = new JComboBox<>(held.toArray(new Database.HeldOrderInfo[0]));
        combo.setSelectedIndex(0);
        int result = JOptionPane.showConfirmDialog(
                this,
                combo,
                "Select a held order to retrieve",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (result == JOptionPane.OK_OPTION) {
            Database.HeldOrderInfo sel = (Database.HeldOrderInfo) combo.getSelectedItem();
            if (sel != null) {
                HashMap<String, Integer> items = Database.retrieveHeldOrder(sel.id);
                if (!items.isEmpty()) {
                    currentTransaction = items;
                    totalAmount = calculateTotal();
                    updateDisplay();
                    JOptionPane.showMessageDialog(this,
                            "Order retrieved successfully.",
                            "Order Retrieved",
                            JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this,
                            "Order could not be retrieved (it may have been removed).",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void voidItem() {
        if (currentTransaction.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No items in the cart to subtract.",
                    "Void Item",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Build options: show UPC - Description (qty N)
        java.util.List<Object> options = new java.util.ArrayList<>();
        class Option {
            final String upc;
            final String label;

            Option(String upc, String label) {
                this.upc = upc;
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        for (var entry : currentTransaction.entrySet()) {
            String upc = entry.getKey();
            int qty = entry.getValue();
            Item item = Database.getItem(upc);
            String desc = (item != null) ? item.description : "(Unknown Item)";
            String label = String.format("%s - %s (qty %d)", upc, truncateString(desc, 40), qty);
            options.add(new Option(upc, label));
        }

        JComboBox<Object> combo = new JComboBox<>(options.toArray());
        combo.setSelectedIndex(0);

        int result = JOptionPane.showConfirmDialog(
                this,
                combo,
                "Select item to subtract",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );

        if (result == JOptionPane.OK_OPTION) {
            Object sel = combo.getSelectedItem();
            if (sel instanceof Option) {
                String upc = ((Option) sel).upc;
                Integer qty = currentTransaction.get(upc);
                if (qty != null) {
                    Item item = Database.getItem(upc);
                    String desc = (item != null) ? item.description : "(Unknown Item)";

                    // New behavior: remove all quantity for the selected item
                    currentTransaction.remove(upc);
                    System.out.printf("[ITEM REMOVED] %s (%s) removed from cart (all quantity voided)%n",
                            upc, truncateString(desc, 40));

                    // Recompute amounts and print important parts
                    totalAmount = calculateTotal();
                    BigDecimal subtotal = totalAmount.setScale(2, RoundingMode.HALF_UP);
                    BigDecimal tax = calculateTax(subtotal);
                    BigDecimal grandTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal taxRatePct = calculateTax(BigDecimal.ONE)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP);

                    System.out.println("---- CART UPDATED ----");
                    System.out.printf("Subtotal: $%.2f%n", subtotal.doubleValue());
                    System.out.printf("Tax (%.2f%%): $%.2f%n", taxRatePct.doubleValue(), tax.doubleValue());
                    System.out.printf("Total: $%.2f%n", grandTotal.doubleValue());
                    System.out.println("----------------------");

                    updateDisplay();
                }
            }
        }
    }

    private BigDecimal calculateTax(BigDecimal total) {
        BigDecimal taxRate = BigDecimal.valueOf(0.07);
        return total.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    }

    private void printReceipt(BigDecimal amountTendered, BigDecimal change, String paymentMethod) {
        // Define colors for receipt
        Color receiptBgColor = new Color(255, 253, 245); // Slight cream color for receipt paper
        Color receiptTextColor = new Color(50, 50, 50);  // Dark gray for text

        // Calculate amounts (prefer the last computed amounts during finishTransaction)
        BigDecimal subtotal = (lastSubtotalUsed != null) ? lastSubtotalUsed : totalAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = (lastTaxUsed != null) ? lastTaxUsed : calculateTax(subtotal);
        BigDecimal grandTotal = (lastGrandTotalUsed != null) ? lastGrandTotalUsed : subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxRatePct = calculateTax(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        // Create styled receipt
        StringBuilder receipt = new StringBuilder();

        // Receipt header
        receipt.append("\n");
        receipt.append("           MOCK REGISTER STORE\n");
        receipt.append("           OFFICIAL RECEIPT\n\n");

        // Receipt details
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        receipt.append(String.format("Date: %s\n", dateTime));
        receipt.append(String.format("Transaction #: %s\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))));
        receipt.append("Cashier: SYSTEM\n");
        receipt.append("\n");

        // Receipt items header
        receipt.append(String.format("%-5s %-40s %10s %12s\n", "QTY", "ITEM", "PRICE", "AMOUNT"));
        receipt.append("=".repeat(70)).append("\n");

        // Receipt items
        for (var entry : currentTransaction.entrySet()) {
            Item item = Database.getItem(entry.getKey());
            if (item != null) {
                BigDecimal lineSubtotal = item.price.multiply(new BigDecimal(entry.getValue()))
                        .setScale(2, RoundingMode.HALF_UP);
                receipt.append(String.format("%-5d %-40s %10.2f %12.2f\n",
                        entry.getValue(),
                        truncateString(item.description, 40),
                        item.price.doubleValue(),
                        lineSubtotal.doubleValue()));
            }
        }

        // Discounts section (if applied)
        if (lastAppliedDiscounts != null && !lastAppliedDiscounts.isEmpty()) {
            receipt.append("\n");
            receipt.append("Discounts Applied\n");
            for (DiscountResult.DiscountLine dl : lastAppliedDiscounts) {
                BigDecimal amt = (dl.amount == null ? BigDecimal.ZERO : dl.amount.abs());
                receipt.append(String.format("  %-56s -%s\n", truncateString(dl.description, 50), amt.toPlainString()));
            }
            BigDecimal discTotal = (lastAppliedDiscountTotal != null) ? lastAppliedDiscountTotal : BigDecimal.ZERO;
            receipt.append(String.format("%58s -%.2f\n", "TOTAL DISCOUNT:", discTotal.setScale(2, RoundingMode.HALF_UP).doubleValue()));
        }

        // Receipt footer (subtotal, tax, total)
        receipt.append("-".repeat(70)).append("\n");
        receipt.append(String.format("%58s %12.2f\n", "SUBTOTAL:", subtotal.doubleValue()));
        receipt.append(String.format("%52s (%d%%): %12.2f\n", "TAX", taxRatePct.intValue(), tax.doubleValue()));
        receipt.append(String.format("%58s %12.2f\n", "TOTAL:", grandTotal.doubleValue()));
        receipt.append("\n");
        receipt.append("Payment Method: ").append(paymentMethod == null ? "CASH" : paymentMethod).append("\n");
        receipt.append(String.format("Amount Tendered: $%.2f\n", amountTendered.doubleValue()));
        receipt.append(String.format("Change: $%.2f\n", change.doubleValue()));
        receipt.append("\n");
        receipt.append("=".repeat(70)).append("\n");
        receipt.append("           Thank you for shopping with us!\n");
        receipt.append("              Please come again soon.\n");
        receipt.append("\n");

        // Create styled receipt area
        JTextArea receiptArea = new JTextArea(receipt.toString());
        receiptArea.setEditable(false);
        receiptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        receiptArea.setBackground(receiptBgColor);
        receiptArea.setForeground(receiptTextColor);
        receiptArea.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Create styled scroll pane
        JScrollPane scrollPane = new JScrollPane(receiptArea);
        scrollPane.setPreferredSize(new Dimension(800, 500));
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));

        // Create custom dialog for receipt
        JDialog receiptDialog = new JDialog(this, "Receipt Preview", true);
        receiptDialog.setLayout(new BorderLayout());
        receiptDialog.add(scrollPane, BorderLayout.CENTER);

        // Add print and close buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(receiptBgColor);

        JButton printButton = new JButton("Print");
        printButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(receiptDialog,
                    "Receipt sent to printer.",
                    "Printing",
                    JOptionPane.INFORMATION_MESSAGE);
            receiptDialog.dispose();
        });

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> receiptDialog.dispose());

        buttonPanel.add(printButton);
        buttonPanel.add(closeButton);
        receiptDialog.add(buttonPanel, BorderLayout.SOUTH);

        // Show dialog
        receiptDialog.pack();
        receiptDialog.setLocationRelativeTo(this);
        receiptDialog.setVisible(true);
    }

    private void printReceipt() {
        printReceipt(totalAmount, BigDecimal.ZERO, "PENDING");
    }

    private void refreshPopularityFromDb() {
        java.util.Map<String, Integer> latest = Database.getPopularityByUpcFromTransactions();
        popularityCounts.clear();
        if (latest != null && !latest.isEmpty()) {
            popularityCounts.putAll(latest);
        }
    }


    private void showTransactionHistory() {
        // Define colors for transaction history
        Color primaryColor = new Color(41, 128, 185); // Blue
        Color backgroundColor = new Color(236, 240, 241); // Light gray background
        Color textColor = new Color(44, 62, 80); // Dark blue-gray for text

        if (transactionsDialog == null) {
            // Create a styled dialog
            transactionsDialog = new JDialog(this, "Transaction History", false);
            transactionsDialog.setSize(900, 600);
            transactionsDialog.setLayout(new BorderLayout());
            transactionsDialog.getContentPane().setBackground(backgroundColor);

            // Create header panel
            JPanel headerPanel = new JPanel(new BorderLayout());
            headerPanel.setBackground(primaryColor);
            headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

            JLabel titleLabel = new JLabel("Transaction History");
            titleLabel.setFont(new Font("Arial", Font.BOLD, 20));
            titleLabel.setForeground(Color.WHITE);
            headerPanel.add(titleLabel, BorderLayout.WEST);

            // Create styled text area
            transactionsTextArea = new JTextArea();
            transactionsTextArea.setEditable(false);
            transactionsTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            transactionsTextArea.setBackground(Color.WHITE);
            transactionsTextArea.setForeground(textColor);
            transactionsTextArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Create styled scroll pane
            JScrollPane scrollPane = new JScrollPane(transactionsTextArea);
            scrollPane.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Create button panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.setBackground(backgroundColor);
            buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            // Create styled refresh button
            JButton refreshButton = createStyledButton("Refresh", primaryColor, Color.WHITE);
            refreshButton.addActionListener(e -> updateTransactionHistory());

            // Create styled close button
            JButton closeButton = createStyledButton("Close", new Color(149, 165, 166), Color.WHITE);
            closeButton.addActionListener(e -> transactionsDialog.setVisible(false));

            // Add buttons to panel
            buttonPanel.add(refreshButton);
            buttonPanel.add(closeButton);

            // Add components to dialog
            transactionsDialog.add(headerPanel, BorderLayout.NORTH);
            transactionsDialog.add(scrollPane, BorderLayout.CENTER);
            transactionsDialog.add(buttonPanel, BorderLayout.SOUTH);

            // Center the dialog relative to the main window
            transactionsDialog.setLocationRelativeTo(this);
        }

        // Update and show dialog
        updateTransactionHistory();
        transactionsDialog.setVisible(true);
    }

    private void updateTransactionHistory() {
        // Get transaction history from database
        String historyText = Database.getTransactionHistory();

        // If empty, show a message
        if (historyText == null || historyText.trim().isEmpty()) {
            transactionsTextArea.setText("No transaction history available.");
            return;
        }

        // Format the transaction history with better styling
        StringBuilder formattedHistory = new StringBuilder();
        formattedHistory.append("TRANSACTION HISTORY\n");
        formattedHistory.append("=".repeat(80)).append("\n\n");
        formattedHistory.append(historyText);

        // Set the formatted text
        transactionsTextArea.setText(formattedHistory.toString());

        // Scroll to the top
        transactionsTextArea.setCaretPosition(0);
    }

    /**
     * Completes the sale:
     * - Prompts for payment method and amount.
     * - Computes tax, change, and persists the transaction.
     * - Prints receipt and resets UI state.
     * - Refreshes quick-key popularity from the database to keep shortcuts relevant.
     */
    private void finishTransaction() {
            // Reset last discount/total context at the start of a new payment flow
            lastAppliedDiscounts = null;
            lastAppliedDiscountTotal = null;
            lastSubtotalUsed = null;
            lastTaxUsed = null;
            lastGrandTotalUsed = null;
        java.util.Map<String, Integer> snapshot = new java.util.HashMap<>(currentTransaction);
        if (totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(this,
                    "No active transaction to complete.",
                    "Payment",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Calculate amounts
        BigDecimal subtotal = totalAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = calculateTax(subtotal);
        BigDecimal grandTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
        BigDecimal taxRatePct = calculateTax(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        // Ask to apply discounts before proceeding with payment
        int apply = JOptionPane.showConfirmDialog(
                this,
                "Apply available discounts before payment?",
                "Discounts",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (apply == JOptionPane.YES_OPTION) {
            DiscountResult r = applyDiscountsViaService();
            if (r == null) {
                JOptionPane.showMessageDialog(this, "Discount service unavailable. Proceeding without discounts.", "Discounts", JOptionPane.WARNING_MESSAGE);
            } else {
                boolean hasDiscounts = (r.totalDiscount != null && r.totalDiscount.compareTo(BigDecimal.ZERO) != 0)
                        || (r.lineItems != null && !r.lineItems.isEmpty());
                if (!hasDiscounts) {
                    JOptionPane.showMessageDialog(this,
                            "No discounts available for these items. Proceeding without discounts.",
                            "Discounts",
                            JOptionPane.INFORMATION_MESSAGE);
                } else {
                    String[] dOpts = {"Apply All", "Choose...", "Skip"};
                    int dChoice = JOptionPane.showOptionDialog(
                            this,
                            "Discounts were found. Apply all discounts, choose specific ones, or skip?",
                            "Discounts",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.QUESTION_MESSAGE,
                            null,
                            dOpts,
                            dOpts[0]
                    );

                    if (dChoice == 0) {
                        // Apply all discounts
                        subtotal = r.discountedTotal.setScale(2, RoundingMode.HALF_UP);
                        tax = calculateTax(subtotal);
                        grandTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);

                        // Track and log discounts
                        lastAppliedDiscounts = (r.lineItems != null) ? new java.util.ArrayList<>(r.lineItems) : null;
                        // Ensure positive discount total for display
                        lastAppliedDiscountTotal = (r.totalDiscount != null) ? r.totalDiscount.abs() : BigDecimal.ZERO;
                        lastSubtotalUsed = subtotal;
                        lastTaxUsed = tax;
                        lastGrandTotalUsed = grandTotal;

                        // Console log and VJ update
                        if (lastAppliedDiscounts != null && !lastAppliedDiscounts.isEmpty()) {
                            System.out.println("== DISCOUNTS APPLIED (All) ==");
                            StringBuilder sbVj = new StringBuilder();
                            System.out.println("Total discount: " + lastAppliedDiscountTotal.toPlainString());
                            System.out.println("New subtotal: " + lastSubtotalUsed.toPlainString());
                            sbVj.append("Total discount: ").append(lastAppliedDiscountTotal.toPlainString()).append("\n");
                        }
                    } else if (dChoice == 1) {
                        java.util.List<DiscountResult.DiscountLine> lines = r.lineItems;
                        if (lines == null || lines.isEmpty()) {
                            JOptionPane.showMessageDialog(this,
                                    "No selectable discounts available.",
                                    "Discounts",
                                    JOptionPane.INFORMATION_MESSAGE);
                        } else {
                            DefaultListModel<String> model = new DefaultListModel<>();
                            for (DiscountResult.DiscountLine dl : lines) {
                                model.addElement(dl.description + " (" + dl.amount.toPlainString() + ")");
                            }
                            JList<String> list = new JList<>(model);
                            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                            JScrollPane sp = new JScrollPane(list);
                            sp.setPreferredSize(new Dimension(350, 150));

                            while (true) {
                                int res = JOptionPane.showConfirmDialog(
                                        this,
                                        sp,
                                        "Select Discounts (Ctrl/Cmd-click for multiple)",
                                        JOptionPane.OK_CANCEL_OPTION,
                                        JOptionPane.PLAIN_MESSAGE
                                );
                                if (res != JOptionPane.OK_OPTION) {
                                    // User pressed Cancel or closed dialog; do not apply any selected discounts
                                    break;
                                }
                                int[] idx = list.getSelectedIndices();
                                if (idx == null || idx.length == 0) {
                                    JOptionPane.showMessageDialog(
                                            this,
                                            "Please select at least one discount or press Cancel.",
                                            "Select Discounts",
                                            JOptionPane.WARNING_MESSAGE
                                    );
                                    // Re-open the selection dialog to enforce a choice
                                    continue;
                                }

                                BigDecimal selectedTotal = BigDecimal.ZERO;
                                java.util.List<DiscountResult.DiscountLine> selectedLines = new java.util.ArrayList<>();

                                // Sum chosen discounts and capture lines
                                for (int i : idx) {
                                    DiscountResult.DiscountLine dl = lines.get(i);
                                    BigDecimal amt = (dl.amount == null ? BigDecimal.ZERO : dl.amount.abs());
                                    selectedTotal = selectedTotal.add(amt);
                                    DiscountResult.DiscountLine copy = new DiscountResult.DiscountLine();
                                    copy.description = dl.description;
                                    copy.amount = amt;
                                    selectedLines.add(copy);
                                }

                                BigDecimal newSubtotal = subtotal.subtract(selectedTotal);
                                if (newSubtotal.signum() < 0) {
                                    newSubtotal = BigDecimal.ZERO;
                                }

                                subtotal = newSubtotal.setScale(2, RoundingMode.HALF_UP);
                                tax = calculateTax(subtotal).setScale(2, RoundingMode.HALF_UP);
                                grandTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);

                                // Track applied selection for receipt/console/VJ
                                lastAppliedDiscounts = selectedLines;
                                lastAppliedDiscountTotal = selectedTotal;
                                lastSubtotalUsed = subtotal;
                                lastTaxUsed = tax;
                                lastGrandTotalUsed = grandTotal;

                                // Console and VJ logs
                                System.out.println("== DISCOUNTS APPLIED (Selected) ==");
                                for (DiscountResult.DiscountLine dl2 : selectedLines) {
                                    System.out.println("  " + dl2.description + " -" + dl2.amount.toPlainString());
                                }
                                System.out.println("Total discount: " + selectedTotal.toPlainString());
                                System.out.println("New subtotal: " + lastSubtotalUsed.toPlainString());
                                // Successfully applied selection; exit loop
                                break;
                            }
                        }
                    } else {
                        // Skip: do nothing
                    }
                }

            }
        }

        // Ensure last-used totals are set (even if no discounts were applied)
        if (lastSubtotalUsed == null) lastSubtotalUsed = subtotal;
        if (lastTaxUsed == null) lastTaxUsed = tax;
        if (lastGrandTotalUsed == null) lastGrandTotalUsed = grandTotal;

        // First: select payment type (Credit Card vs Cash)
        String[] payTypeOptions = {"Credit Card", "Cash", "Cancel"};
        int payType = JOptionPane.showOptionDialog(
                this,
                String.format("Subtotal: $%.2f\nTax (%.2f%%): $%.2f\nTotal: $%.2f\n\nChoose payment type:",
                        subtotal.doubleValue(), taxRatePct.doubleValue(), tax.doubleValue(), grandTotal.doubleValue()),
                "Select Payment Type",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                payTypeOptions,
                payTypeOptions[0]
        );

        if (payType == -1 || payType == 2) {
            // Closed dialog or Cancel
            return;
        }

        BigDecimal amountTendered;
        BigDecimal change = BigDecimal.ZERO;
        String paymentMethod;
        String paymentDetail; // "Exact", "Next Dollar", or "Custom"

        if (payType == 0) {
            // Credit Card: auto-charge exact amount
            paymentMethod = "CREDIT CARD";
            paymentDetail = "Exact";
            amountTendered = grandTotal;
            change = BigDecimal.ZERO;
        } else {
            // Cash: ask for method (Exact, Next Dollar, Custom)
            paymentMethod = "CASH";
            String[] options = {"Exact Cash", "Next Dollar", "Custom Amount", "Cancel"};
            String message = String.format(
                    "Subtotal: $%.2f\nTax (%.2f%%): $%.2f\nTotal: $%.2f\n\nChoose payment method:",
                    subtotal.doubleValue(), taxRatePct.doubleValue(), tax.doubleValue(), grandTotal.doubleValue()
            );
            int choice = JOptionPane.showOptionDialog(
                    this,
                    message,
                    "Select Cash Option",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]
            );

            if (choice == -1 || choice == 3) {
                // Closed dialog or Cancel
                return;
            }

            switch (choice) {
                case 0: // Exact Cash
                    paymentDetail = "Exact";
                    amountTendered = grandTotal;
                    break;
                case 1: // Next Dollar
                    paymentDetail = "Next Dollar";
                    amountTendered = grandTotal.setScale(0, RoundingMode.CEILING).setScale(2, RoundingMode.HALF_UP);
                    break;
                case 2: // Custom Amount
                    paymentDetail = "Custom";
                    BigDecimal inputAmt = null;
                    while (true) {
                        String input = JOptionPane.showInputDialog(this,
                                String.format("Subtotal: $%.2f\nTax (%.2f%%): $%.2f\nTotal: $%.2f\n\nEnter amount tendered:",
                                        subtotal.doubleValue(),
                                        taxRatePct.doubleValue(),
                                        tax.doubleValue(),
                                        grandTotal.doubleValue()),
                                "Enter Payment",
                                JOptionPane.QUESTION_MESSAGE);

                        if (input == null) {
                            // User cancelled custom input
                            return;
                        }

                        try {
                            inputAmt = new BigDecimal(input).setScale(2, RoundingMode.HALF_UP);
                            if (inputAmt.compareTo(grandTotal) < 0) {
                                JOptionPane.showMessageDialog(this,
                                        "Amount is less than total. Please enter a valid amount.",
                                        "Invalid Payment",
                                        JOptionPane.WARNING_MESSAGE);
                                continue;
                            }
                            break;
                        } catch (NumberFormatException e) {
                            JOptionPane.showMessageDialog(this,
                                    "Please enter a valid number.",
                                    "Invalid Input",
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    }
                    amountTendered = inputAmt;
                    break;
                default:
                    // Safety
                    return;
            }
            change = amountTendered.subtract(grandTotal).setScale(2, RoundingMode.HALF_UP);
        }

        // Persist the tax-inclusive total as the transaction total
        long transactionId = Database.saveTransaction(currentTransaction, grandTotal);
        if (transactionId != -1) {
            // Console summary of the payment (important parts of the receipt)
            System.out.println("\n=== PAYMENT CONFIRMED ===");
            System.out.printf("Transaction ID: %d%n", transactionId);
            System.out.printf("Method: %s (%s)%n", paymentMethod, paymentDetail);

            System.out.println("Items:");
            for (var entry : currentTransaction.entrySet()) {
                String upc = entry.getKey();
                int qty = entry.getValue();

                Item item = Database.getItem(upc);
                String desc = (item != null && item.getDescription() != null) ? item.getDescription() : upc;
                BigDecimal unitPrice = (item != null && item.getPrice() != null) ? item.getPrice() : BigDecimal.ZERO;
                BigDecimal lineSubtotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

                System.out.printf("  %d x %s @ $%.2f = $%.2f%n",
                        qty,
                        truncateString(desc, 30),
                        unitPrice.doubleValue(),
                        lineSubtotal.doubleValue());
            }

            BigDecimal discountToShow = (lastAppliedDiscountTotal != null) ? lastAppliedDiscountTotal : BigDecimal.ZERO;
            if (discountToShow.compareTo(BigDecimal.ZERO) > 0) {
                System.out.printf("Discounts: -$%.2f%n", discountToShow.doubleValue());
            } else {
                System.out.println("Discounts: $0.00");
            }

            System.out.printf("Subtotal: $%.2f%n", subtotal.doubleValue());

            System.out.printf("Tax (%.2f%%): $%.2f%n", taxRatePct.doubleValue(), tax.doubleValue());
            System.out.printf("Total: $%.2f%n", grandTotal.doubleValue());
            System.out.printf("Amount Tendered: $%.2f%n", amountTendered.doubleValue());
            System.out.printf("Change: $%.2f%n", change.doubleValue());
            System.out.println("=========================\n");

            printReceipt(amountTendered, change, paymentMethod);
            currentTransaction.clear();
            totalAmount = BigDecimal.ZERO;
            updateDisplay();

            if (transactionsDialog != null && transactionsDialog.isVisible()) {
                updateTransactionHistory();
            }
        }

        refreshPopularityFromDb();

        reassignQuickKeysByPopularity();

        // Rebuild and cache the static popular section now that assignments changed
        javax.swing.SwingUtilities.invokeLater(this::showPopularShortcutsInJournal);
    }

    private DiscountResult applyDiscountsViaService() {
        try {
            // Build JSON: { "items": [ { upc, description, unitPrice, quantity }, ... ] }
            ObjectNode root = JSON.createObjectNode();
            ArrayNode items = root.putArray("items");

            for (var e : currentTransaction.entrySet()) {
                String upc = e.getKey();
                int qty = e.getValue();
                if (qty <= 0) continue;

                Item item = Database.getItem(upc);
                if (item == null) continue;

                ObjectNode it = items.addObject();
                it.put("upc", upc);
                it.put("description", item.getDescription());
                it.put("unitPrice", item.getPrice()); // Jackson will handle BigDecimal
                it.put("quantity", qty);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(discountEndpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(root)))
                    .build();

            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                // Parse response: { discounts:[{description,amount}], totalDiscount, discountedTotal }
                ObjectNode r = (ObjectNode) JSON.readTree(resp.body());
                DiscountResult result = new DiscountResult();
                result.totalDiscount = new BigDecimal(r.get("totalDiscount").asText());
                result.discountedTotal = new BigDecimal(r.get("discountedTotal").asText());

                var arr = r.withArray("discounts");
                StringBuilder lines = new StringBuilder();
                java.util.List<DiscountResult.DiscountLine> parsed = new java.util.ArrayList<>();
                for (int i = 0; i < arr.size(); i++) {
                    var d = (ObjectNode) arr.get(i);
                    String desc = d.get("description").asText();
                    String amtStr = d.get("amount").asText();
                    BigDecimal amt = new BigDecimal(amtStr);
                    if (amt.compareTo(BigDecimal.ZERO) != 0) {
                        lines.append(desc)
                                .append(" ")
                                .append(amtStr)
                                .append("\n");
                        DiscountResult.DiscountLine line = new DiscountResult.DiscountLine();
                        line.description = desc;
                        line.amount = amt;
                        parsed.add(line);
                    }
                }
                result.lines = lines.toString();
                result.lineItems = parsed;
                return result;
            } else {
                System.err.println("Discount service error: HTTP " + resp.statusCode() + " -> " + resp.body());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    // Small holder for discount results
    private static final class DiscountResult {
        static final class DiscountLine {
            String description;
            BigDecimal amount;
        }
        BigDecimal totalDiscount;
        BigDecimal discountedTotal;
        String lines; // legacy string for quick journal output
        java.util.List<DiscountLine> lineItems; // parsed individual discounts
    }
}