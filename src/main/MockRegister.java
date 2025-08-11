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


public class MockRegister extends JFrame {
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

    private static final int FAST_GAP_MS = 50;
    private static final int INACTIVITY_COMMIT_MS = 100;
    private static final int MIN_SCANNER_LEN = 2;
    private static final int MAX_SCANNER_LEN = 64;
    private static final String POPULAR_HEADER = "=".repeat(36) + " Popular Shortcuts " + "=".repeat(35);
    private static final String POPULAR_FOOTER = "=".repeat(90);
    private volatile boolean suppressDisplayUpdates = false;
    private String cachedPopularSection = null;
    private javax.swing.JTextField manualBarcodeField;
    private javax.swing.JButton manualAddButton;


    public MockRegister() {
        currentTransaction = new HashMap<>();
        totalAmount = BigDecimal.ZERO;

        // Initialize database and load price book
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
    }

    private void initUI() {
        // Set application properties
        setTitle("Mock Register");
        setSize(800, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Set system look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Define color scheme
        Color primaryColor = new Color(41, 128, 185); // Blue
        Color secondaryColor = new Color(52, 152, 219); // Lighter blue
        Color accentColor = new Color(231, 76, 60);  // Red for cancel/important actions
        Color backgroundColor = new Color(236, 240, 241); // Light gray background
        Color textColor = new Color(44, 62, 80); // Dark blue-gray for text

        // Set frame background
        getContentPane().setBackground(backgroundColor);

        // Header (title + datetime)
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

        // Manual barcode entry (below header)
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

        manualLabel.setLabelFor(manualBarcodeField);

        // Wire actions
        ActionListener manualAddAction = e -> addManualBarcode();
        manualBarcodeField.addActionListener(manualAddAction);
        manualAddButton.addActionListener(manualAddAction);

        // Optional: ESC clears field
        manualBarcodeField.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "clearField");
        manualBarcodeField.getActionMap().put("clearField", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                manualBarcodeField.setText("");
            }
        });

        manualPanel.add(manualLabel, BorderLayout.WEST);
        manualPanel.add(manualBarcodeField, BorderLayout.CENTER);
        manualPanel.add(manualAddButton, BorderLayout.EAST);

        // Wrap header + manual into a single north container
        JPanel northContainer = new JPanel(new BorderLayout());
        northContainer.setBackground(backgroundColor);
        northContainer.add(headerPanel, BorderLayout.NORTH);
        northContainer.add(manualPanel, BorderLayout.SOUTH);

        // Journal area
        virtualJournal = new JTextArea();
        virtualJournal.setEditable(false);
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
        subtractButton = createStyledButton("Subtract Item", new Color(192, 57, 43), Color.WHITE);

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
        mainPanel.add(scrollPane, BorderLayout.CENTER);

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

        // Focus manual field for quick typed entries
        javax.swing.SwingUtilities.invokeLater(() -> manualBarcodeField.requestFocusInWindow());
    }

    private void addManualBarcode() {
        if (manualBarcodeField == null) return;
        String code = manualBarcodeField.getText();
        if (code != null) code = code.trim();

        if (code == null || code.isEmpty()) {
            javax.swing.SwingUtilities.invokeLater(() -> manualBarcodeField.requestFocusInWindow());
            return;
        }

        // Basic sanity checks consistent with scanner handling
        if (code.length() < MIN_SCANNER_LEN || code.length() > MAX_SCANNER_LEN) {
            javax.swing.JOptionPane.showMessageDialog(
                    this,
                    "Invalid code length.",
                    "Barcode",
                    javax.swing.JOptionPane.WARNING_MESSAGE
            );
            manualBarcodeField.selectAll();
            manualBarcodeField.requestFocusInWindow();
            return;
        }

        // Attempt to add item (reuses existing quick-key path)
        addItemByQuickKey(code);

        // Clear and refocus for fast repeated entries
        manualBarcodeField.setText("");
        javax.swing.SwingUtilities.invokeLater(() -> manualBarcodeField.requestFocusInWindow());
    }

    private void initializePopularShortcuts() {
        refreshPopularityFromDb();
        reassignQuickKeysByPopularity();
        showPopularShortcutsInJournal();
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

    private void reassignQuickKeysByPopularity() {
        javax.swing.JRootPane root = getRootPane();
        if (root == null) return;

        // Rank UPCs by popularity (desc), tie-break by description then UPC for stability
        java.util.List<java.util.Map.Entry<String, Integer>> ranked = new java.util.ArrayList<>(popularityCounts.entrySet());
        ranked.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            main.Item ia = Database.getItem(a.getKey());
            main.Item ib = Database.getItem(b.getKey());
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
    }

    private void setupQuickItemShortcuts() {
        quickKeysByUpc.clear();

        java.util.List<String> topUPCs = Database.getTopSellingUPCs(12);
        if (topUPCs == null || topUPCs.isEmpty() || getRootPane() == null) {
            // Still refresh the journal so it shows “no shortcut” state cleanly
            javax.swing.SwingUtilities.invokeLater(this::showPopularShortcutsInJournal);
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

        javax.swing.SwingUtilities.invokeLater(this::showPopularShortcutsInJournal);
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
    private void showPopularShortcutsInJournal() {
        // Build the popular shortcuts section text
        StringBuilder psb = new StringBuilder();
        psb.append(POPULAR_HEADER).append("\n");

        if (quickKeysByUpc == null || quickKeysByUpc.isEmpty()) {
            psb.append("No shortcuts configured.\n");
        } else {
            // Helper: resolve the F-key index for a keystroke (F1 -> 0, ..., F12 -> 11; non-F -> 999)
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
                main.Item iA = Database.getItem(upc1);
                main.Item iB = Database.getItem(upc2);
                String dA = iA != null ? iA.description : "";
                String dB = iB != null ? iB.description : "";
                cmp = dA.compareToIgnoreCase(dB);
                if (cmp != 0) return cmp;
                return upc1.compareTo(upc2);
            });

            // Print in F1 -> F12 order (by the sort above), prefixing the first F-key label
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

    private void setupKeyListener() {
        // Timer that fires when no more fast characters arrive
        burstInactivityTimer = new Timer(INACTIVITY_COMMIT_MS, e -> commitFastBurstIfValid());
        burstInactivityTimer.setRepeats(false);

        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            @Override
            public void eventDispatched(AWTEvent event) {
                if (!(event instanceof KeyEvent)) return;
                KeyEvent ke = (KeyEvent) event;

                // Use KEY_TYPED to get character data
                if (ke.getID() != KeyEvent.KEY_TYPED) return;

                char ch = ke.getKeyChar();

                // Ignore non-printable characters except Enter (handled below)
                boolean isPrintable = ch != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(ch);
                boolean isEnter = (ch == '\n' || ch == '\r');

                long now = System.nanoTime();

                // If Enter arrives shortly after fast input, commit immediately
                if (isEnter) {
                    if (hasRecentFastInput(now)) {
                        commitFastBurstIfValid();
                    } else {
                        // Enter with no fast context: ignore
                        clearIfStale(now);
                    }
                    return;
                }

                // Accept only typical scanner characters (alphanumeric). Adjust if needed.
                if (!isPrintable || !(Character.isLetterOrDigit(ch))) {
                    // Non-eligible char ends any fast burst due to gap classification
                    clearIfStale(now);
                    return;
                }

                // Determine gap w.r.t. last fast char
                boolean continuesFastBurst = lastFastCharNanos > 0 &&
                        ((now - lastFastCharNanos) / 1_000_000L) <= FAST_GAP_MS;

                if (!continuesFastBurst) {
                    // Start a new fast burst (discard previous if pending)
                    commitFastBurstIfValid(); // this safely does nothing if buffer invalid/empty
                    fastBurstBuffer.setLength(0);
                }

                // Append to fast burst
                if (fastBurstBuffer.length() < MAX_SCANNER_LEN) {
                    fastBurstBuffer.append(ch);
                }
                lastFastCharNanos = now;

                // Restart inactivity timer so we commit shortly after the scan finishes
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

    private void commitFastBurstIfValid() {
        if (fastBurstBuffer.length() >= MIN_SCANNER_LEN) {
            String upc = fastBurstBuffer.toString().trim();
            fastBurstBuffer.setLength(0);
            lastFastCharNanos = 0L;

            System.out.println("\n[SCAN GUN] Scanning UPC: " + upc);

            Item item = Database.getItem(upc);
            if (item != null) {
                System.out.println("[SCAN GUN] Item found: " + item.description + " - $" + item.price);
                currentTransaction.merge(upc, 1, Integer::sum);
                totalAmount = calculateTotal();
                updateDisplay();
            } else {
                System.out.println("[SCAN GUN] ERROR: Item not found for UPC: " + upc);
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

        boolean hasItems = subtotal.compareTo(BigDecimal.ZERO) > 0;

        if (payButton != null) payButton.setEnabled(hasItems);
        if (cancelButton != null) cancelButton.setEnabled(hasItems);
        if (holdButton != null) holdButton.setEnabled(hasItems);
        if (printButton != null) printButton.setEnabled(hasItems);
        if (subtractButton != null) subtractButton.setEnabled(hasItems);
        if (retrieveButton != null) retrieveButton.setEnabled(true);
        if (viewTransactionsButton != null) viewTransactionsButton.setEnabled(true);
    }

    private String truncateString(String input, int maxLength) {
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength - 3) + "...";
    }

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
        String input = JOptionPane.showInputDialog(this,
                "Enter order ID to retrieve:",
                "Retrieve Held Order",
                JOptionPane.QUESTION_MESSAGE);

        if (input != null && !input.trim().isEmpty()) {
            try {
                long orderId = Long.parseLong(input.trim());
                HashMap<String, Integer> items = Database.retrieveHeldOrder(orderId);

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
                            "Order not found.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this,
                        "Please enter a valid number.",
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void voidItem() {
        if (currentTransaction.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No items in the cart to subtract.",
                    "Subtract Item",
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

                    if (qty > 1) {
                        currentTransaction.put(upc, qty - 1);
                        System.out.printf("[ITEM SUBTRACTED] %s (%s): qty %d -> %d%n",
                                upc, truncateString(desc, 40), qty, qty - 1);
                    } else {
                        currentTransaction.remove(upc);
                        System.out.printf("[ITEM REMOVED] %s (%s) removed from cart%n",
                                upc, truncateString(desc, 40));
                    }

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

        // Calculate amounts
        BigDecimal subtotal = totalAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = calculateTax(subtotal);
        BigDecimal grandTotal = subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
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
        scrollPane.setPreferredSize(new Dimension(500, 500));
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

    private void finishTransaction() {
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
}