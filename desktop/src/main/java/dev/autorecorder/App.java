package dev.autorecorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import dev.autorecorder.attempt.AttemptMachine;
import dev.autorecorder.integration.EventServer;
import dev.autorecorder.minecraft.HermesMonitor;
import dev.autorecorder.model.Attempt;
import dev.autorecorder.obs.ObsClient;
import dev.autorecorder.obs.ObsConnectionConfig;
import dev.autorecorder.obs.ObsHostDiscovery;
import dev.autorecorder.obs.ObsPasswordStore;
import dev.autorecorder.obs.ObsQrParser;
import dev.autorecorder.persistence.Database;
import dev.autorecorder.persistence.AuditEntry;
import dev.autorecorder.recording.RecorderConfig;
import dev.autorecorder.recording.RecordingEntry;
import dev.autorecorder.recording.QuarantineManager;
import dev.autorecorder.recording.SourceRecordService;
import dev.autorecorder.retention.RetentionConfig;
import dev.autorecorder.retention.RetentionPolicy;
import dev.autorecorder.storage.StorageMonitor;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.input.Clipboard;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

public final class App extends Application {
    private static final Duration SHORT_EMPTY_WALL_CLIP_MAX_DURATION = Duration.ofSeconds(10);

    private Database db;
    private ObsPasswordStore obsPasswordStore;
    private final ObsClient obs = new ObsClient();
    private final SourceRecordService sourceRecord = new SourceRecordService(obs);
    private final QuarantineManager quarantine = new QuarantineManager();
    private final RetentionPolicy retentionPolicy = new RetentionPolicy();
    private final StorageMonitor storageMonitor = new StorageMonitor();
    private final ScheduledExecutorService storageExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "storage-monitor"); t.setDaemon(true); return t;
    });
    private final AttemptMachine machine = new AttemptMachine();
    private final AtomicReference<Attempt> current = new AtomicReference<>();
    private final AtomicReference<RetentionConfig> retention = new AtomicReference<>(RetentionConfig.safeDefaults());
    private final AtomicReference<RecorderConfig> recorder = new AtomicReference<>(RecorderConfig.safeDefaults());
    private final ExecutorService recordingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "recording-lifecycle"); t.setDaemon(true); return t;
    });
    private final ConcurrentHashMap<UUID, SourceRecordService.Handle> activeGameplay = new ConcurrentHashMap<>();
    private final Set<UUID> startingGameplay = ConcurrentHashMap.newKeySet();
    private final Set<UUID> stoppingGameplay = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingGameplayStops = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> attemptNumbers = new ConcurrentHashMap<>();
    private volatile SourceRecordService.Handle wallHandle;
    private volatile boolean wallStartQueued;
    private volatile boolean wallStopQueued;
    private volatile boolean wallStopPending;
    private volatile UUID restartWallSessionId;
    private volatile boolean shuttingDown;
    private volatile boolean minecraftSessionActive;
    private volatile boolean seedQueueOnWall;
    private volatile String seedQueueState = "";
    private volatile boolean wallSegmentStartedThisSession;
    private volatile UUID activeWallBatchId;
    private volatile long nextAttemptNumber;
    private Label obsStatus, gameStatus, seedQueueStatus, attemptStatus, netherStatus, gameplayStatus, wallStatus, storageStatus, sourceRecordStatus;
    private Label recordingNotice;
    private TrayIcon trayIcon;
    private ListView<Attempt> historyView;
    private volatile Map<UUID, RecordingEntry> latestRecordingByAttempt = Map.of();
    private EventServer events;
    private HermesMonitor hermes;
    private UUID session = UUID.randomUUID();

    @Override public void start(Stage stage) throws Exception {
        Path data = Path.of(System.getProperty("user.home"), ".mcsr-autorecorder");
        db = new Database(data.resolve("manager.sqlite"));
        obsPasswordStore = new ObsPasswordStore(data.resolve("obs-password.dpapi"));
        nextAttemptNumber = db.countAttempts();
        retention.set(db.loadRetentionConfig());
        recorder.set(db.loadRecorderConfig());

        obsStatus = new Label("Desconectado");
        sourceRecordStatus = new Label("Source Record: verificando...");
        gameStatus = new Label("Procurando a instância Hermes aprovada...");
        seedQueueStatus = new Label("SeedQueue: aguardando o marcador da wall");
        attemptStatus = new Label("Nenhuma tentativa ativa");
        netherStatus = new Label("Nether: unknown — waiting for SpeedRunIGT record");
        gameplayStatus = new Label("Tentativas: gravação automática desligada");
        wallStatus = new Label("Gravação SeedQueue: aguardando a wall");
        recordingNotice = new Label("Avisos: aguardando a próxima gravação");
        storageStatus = new Label("Storage: checking managed recordings folder…");
        refreshRecordingLabels();

        ObsConnectionConfig obsConnection = db.loadObsConnectionConfig();
        boolean reconnectOnLaunch = db.hasSavedObsConnectionConfig();
        TextField host = new TextField(obsConnection.host());
        TextField port = new TextField(Integer.toString(obsConnection.port()));
        PasswordField password = new PasswordField();
        try {
            var savedPassword = obsPasswordStore.load();
            password.setText(savedPassword.orElse(""));
            if (reconnectOnLaunch && savedPassword.isEmpty())
                obsStatus.setText("OBS: informe a senha uma vez para salvá-la neste computador");
        }
        catch (java.io.IOException error) {
            obsStatus.setText("OBS: não foi possível recuperar a senha salva; informe-a novamente");
        }
        Button connect = new Button("Conectar ao OBS");
        connect.getStyleClass().add("primary-button");
        Button disconnect = new Button("Desconectar");
        disconnect.setOnAction(e -> {
            obs.close();
            obsStatus.setText("Desconectado; gravações pendentes serão recuperadas ao reconectar");
            connect.setDisable(false);
        });
        Button pasteQr = new Button("Colar QR do OBS");
        Button autoIp = new Button("Auto preencher IP");
        autoIp.setTooltip(new Tooltip("Procura a porta OBS configurada neste computador. Para OBS em outro PC, use o QR."));
        autoIp.setOnAction(e -> {
            final int targetPort;
            try {
                targetPort = Integer.parseInt(port.getText().trim());
                if (targetPort < 1 || targetPort > 65535) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                obsStatus.setText("OBS: informe uma porta válida antes de procurar o IP");
                return;
            }

            autoIp.setDisable(true);
            obsStatus.setText("OBS: procurando o WebSocket neste computador…");
            CompletableFuture.supplyAsync(() -> {
                try { return ObsHostDiscovery.discoverLocalHost(targetPort); }
                catch (java.net.SocketException ex) { throw new java.util.concurrent.CompletionException(ex); }
            }).whenComplete((found, error) -> Platform.runLater(() -> {
                autoIp.setDisable(false);
                if (error != null) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    obsStatus.setText("OBS: não foi possível listar os endereços locais — " + cause.getMessage());
                } else if (found.isPresent()) {
                    host.setText(found.get());
                    obsStatus.setText("OBS: IP local preenchido (" + found.get() + "); clique Connect OBS para validar");
                } else {
                    obsStatus.setText("OBS: nenhum WebSocket respondeu na porta " + targetPort
                            + " neste PC; para outro PC, use Colar QR do OBS");
                }
            }));
        });
        pasteQr.setOnAction(e -> {
            try {
                ObsQrParser.Connection c = ObsQrParser.parse(readQrFromClipboard());
                host.setText(c.host());
                port.setText(Integer.toString(c.port()));
                password.setText(c.password());
                obsStatus.setText(c.authenticationRequired()
                        ? "OBS: dados do QR preenchidos; clique em Conectar"
                        : "OBS: QR sem senha preenchido; clique em Conectar");
            } catch (Exception ex) {
                new Alert(Alert.AlertType.INFORMATION,
                        "Não consegui ler os dados do QR. Copie o texto do QR ou uma imagem para a área de transferência e tente novamente.\n\n"
                                + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });
        connect.setOnAction(e -> {
            connect.setDisable(true);
            obsStatus.setText("OBS: conectando e autenticando…");
            try {
                String enteredHost = host.getText().trim();
                int enteredPort = Integer.parseInt(port.getText().trim());
                db.saveObsConnectionConfig(new ObsConnectionConfig(enteredHost, enteredPort));
                String passwordSaveWarning = "";
                try { obsPasswordStore.save(password.getText()); }
                catch (java.io.IOException error) {
                    passwordSaveWarning = " (senha não pôde ser salva com segurança: " + error.getMessage() + ")";
                }
                final String savedWarning = passwordSaveWarning;
                obs.connect(enteredHost, enteredPort, password.getText())
                        .thenAccept(v -> Platform.runLater(() -> {
                            obsStatus.setText("Conectado e autenticado" + savedWarning);
                            connect.setDisable(false);
                            onObsConnected();
                        })).exceptionally(x -> {
                            String diagnostic = null;
                            for (Throwable t = x; t != null; t = t.getCause()) {
                                if (t.getMessage() != null && !t.getMessage().isBlank() && diagnostic == null)
                                    diagnostic = t.getMessage();
                                if (t instanceof java.net.ConnectException) {
                                    diagnostic = "cannot reach " + host.getText().trim() + ":" + port.getText().trim()
                                            + " — if OBS is on this PC, use 127.0.0.1; otherwise check OBS bind address and firewall";
                                    break;
                                }
                            }
                            if (diagnostic == null) {
                                diagnostic = "Falha no handshake do WebSocket; confira endereço, porta e configuração do OBS";
                                if (password.getText().isBlank())
                                    diagnostic += ". Se a autenticação estiver ativada, informe a senha uma vez para salvá-la";
                            }
                            String errorText = diagnostic;
                            Platform.runLater(() -> {
                                obsStatus.setText("OBS: connection failed — " + errorText);
                                connect.setDisable(false);
                            });
                            return null;
                        });
            } catch (Exception ex) {
                obsStatus.setText("OBS: confira host e porta — " + ex.getMessage());
                connect.setDisable(false);
            }
        });

        ListView<Attempt> history = new ListView<>();
        historyView = history;
        history.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Attempt attempt, boolean empty) {
                super.updateItem(attempt, empty);
                if (empty || attempt == null) { setText(null); setGraphic(null); return; }
                String file = fileName(attempt.recordingPath());
                String retentionText = attempt.retentionDecision() == null ? "Pendente" : attempt.retentionDecision();
                long seconds = attempt.endedAt() == null ? 0 : Math.max(0, Duration.between(attempt.startedAt(), attempt.endedAt()).getSeconds());
                RecordingEntry recording = latestRecordingByAttempt.get(attempt.id());
                String size = recording == null ? "tamanho desconhecido" : formatBytes(recording.fileSize());
                Label world = new Label("Mundo: " + attempt.worldId());
                world.getStyleClass().add("attempt-world");
                world.setTextOverrun(OverrunStyle.ELLIPSIS);
                world.setMaxWidth(Double.MAX_VALUE);
                Label state = new Label(attempt.state().replace('_', ' '));
                state.getStyleClass().addAll("pill", "state-pill");
                Label nether = new Label(attempt.netherReached() ? "Nether confirmado" : "Nether desconhecido");
                nether.getStyleClass().addAll("pill", attempt.netherReached() ? "pill-success" : "pill-muted");
                Label title = new Label(attempt.startedAt().atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
                title.getStyleClass().add("attempt-title");
                HBox heading = new HBox(8, title, state, nether);
                HBox.setHgrow(title, Priority.ALWAYS);
                Label detail = new Label((attempt.endedAt() == null ? "Em andamento" : "Duração: " + seconds + " s")
                        + "   ·   " + file + "   ·   " + size + "   ·   Retenção: " + retentionText
                        + (attempt.important() ? "   ·   IMPORTANTE" : "")
                        + (attempt.excluded() ? "   ·   EXCLUÍdo da limpeza" : ""));
                detail.getStyleClass().add("attempt-detail");
                detail.setTextOverrun(OverrunStyle.ELLIPSIS);
                VBox row = new VBox(5, heading, world, detail);
                row.getStyleClass().add("attempt-row");
                row.setPadding(new Insets(10, 12, 10, 12));
                setText(null);
                setGraphic(row);
            }
        });
        loadHistory(history);
        Button refresh = new Button("Atualizar lista");
        refresh.setOnAction(e -> loadHistory(history));
        Button openVideo = new Button("Abrir vídeo");
        openVideo.setOnAction(e -> openSelectedRecording(history, false));
        Button openFolder = new Button("Abrir pasta");
        openFolder.setOnAction(e -> openSelectedRecording(history, true));
        Button important = new Button("Marcar importante");
        important.setTooltip(new Tooltip("Protege a gravação contra retenção automática"));
        important.setOnAction(e -> toggleSelectedFlag(history, true));
        Button excluded = new Button("Alternar exclusão");
        excluded.setTooltip(new Tooltip("Exclui ou inclui esta gravação nas regras automáticas"));
        excluded.setOnAction(e -> toggleSelectedFlag(history, false));
        Button filters = new Button("Filtros de retenção");
        filters.setOnAction(e -> showRetentionSettings(stage));
        Button previewRetention = new Button("Prévia da limpeza");
        previewRetention.setOnAction(e -> showRetentionPreview(stage));
        Button recordingSetup = new Button("Configurar gravação");
        recordingSetup.setOnAction(e -> showRecordingSettings(stage));
        refreshRecordingLabels();
        Button logs = new Button("Histórico e logs");
        logs.setOnAction(e -> showAuditLog(stage));

        host.setPromptText("127.0.0.1");
        port.setPromptText("4455");
        password.setPromptText("Senha do WebSocket");
        host.setPrefColumnCount(16);
        port.setPrefColumnCount(6);
        password.setPrefColumnCount(14);
        GridPane connectionFields = new GridPane();
        connectionFields.setHgap(12);
        connectionFields.setVgap(6);
        connectionFields.add(fieldGroup("Endereço do OBS", host), 0, 0);
        connectionFields.add(fieldGroup("Porta", port), 1, 0);
        connectionFields.add(fieldGroup("Senha do WebSocket", password), 2, 0);
        ColumnConstraints addressColumn = new ColumnConstraints();
        addressColumn.setHgrow(Priority.ALWAYS);
        addressColumn.setPercentWidth(45);
        ColumnConstraints portColumn = new ColumnConstraints();
        portColumn.setHgrow(Priority.NEVER);
        portColumn.setPercentWidth(18);
        ColumnConstraints passwordColumn = new ColumnConstraints();
        passwordColumn.setHgrow(Priority.ALWAYS);
        passwordColumn.setPercentWidth(37);
        connectionFields.getColumnConstraints().setAll(addressColumn, portColumn, passwordColumn);
        HBox connectionActions = new HBox(8, connect, disconnect, autoIp, pasteQr);
        Label savedConfigHint = new Label("Endereço, porta e senha ficam salvos para reconectar automaticamente ao abrir o app.");
        savedConfigHint.getStyleClass().add("muted-text");
        obsStatus.getStyleClass().add("connection-status");
        VBox connectionCard = card("Conexão com OBS", "WebSocket v5 controla as gravações de gameplay e da sessão.",
                connectionFields, connectionActions, obsStatus, sourceRecordStatus, savedConfigHint);

        GridPane statusGrid = new GridPane();
        statusGrid.setHgap(12);
        statusGrid.setVgap(12);
        statusGrid.add(statusCard("Minecraft", gameStatus, seedQueueStatus), 0, 0);
        statusGrid.add(statusCard("Tentativa atual", attemptStatus, netherStatus), 1, 0);
        statusGrid.add(statusCard("Gravações", gameplayStatus, wallStatus, recordingNotice), 0, 1);
        statusGrid.add(statusCard("Armazenamento", storageStatus), 1, 1);
        ColumnConstraints statusColumnA = new ColumnConstraints();
        statusColumnA.setPercentWidth(50);
        statusColumnA.setHgrow(Priority.ALWAYS);
        ColumnConstraints statusColumnB = new ColumnConstraints();
        statusColumnB.setPercentWidth(50);
        statusColumnB.setHgrow(Priority.ALWAYS);
        statusGrid.getColumnConstraints().setAll(statusColumnA, statusColumnB);

        Label recordingHelp = new Label("Cada vídeo captura a janela inteira do Minecraft: começa quando a wall abre e termina ao voltar para a tela inicial (Title) ou quando a próxima wall abrir. O clipe é mantido se alguma tentativa associada chegou ao Nether; resultados incertos são preservados.");
        recordingHelp.setWrapText(true);
        recordingHelp.getStyleClass().add("muted-text");
        FlowPane settingsActions = new FlowPane(8, 8, recordingSetup, filters, previewRetention, logs);
        Label retentionLimit = new Label("Arquivos elegíveis vão para quarentena quando a limpeza automática está habilitada. A exclusão permanente ainda não está disponível.");
        retentionLimit.setWrapText(true);
        retentionLimit.getStyleClass().add("muted-text");
        VBox managementCard = card("Gravação e retenção", "Um vídeo começa ao abrir a wall e termina na tela inicial ou na próxima abertura da wall.",
                settingsActions, recordingHelp, retentionLimit);

        Label historyHint = new Label("Selecione uma tentativa para abrir o vídeo, a pasta ou alterar sua proteção.");
        historyHint.getStyleClass().add("muted-text");
        FlowPane historyActions = new FlowPane(8, 8, refresh, openVideo, openFolder, important, excluded);
        VBox historyCard = card("Tentativas recentes", historyHint, history, historyActions);
        history.setPrefHeight(350);
        VBox page = new VBox(16, pageHeader(), statusGrid, connectionCard, managementCard, historyCard);
        page.getStyleClass().add("page-content");
        page.setPadding(new Insets(24));
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        stage.setTitle("MCSR AutoRecorder");
        stage.setMinWidth(900);
        stage.setMinHeight(700);
        Scene scene = new Scene(scroll, 1120, 900);
        var stylesheet = getClass().getResource("/dev/autorecorder/app.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        stage.show();
        installSystemTray();
        if (reconnectOnLaunch) Platform.runLater(connect::fire);

        storageExecutor.scheduleWithFixedDelay(this::refreshStorageStatus, 0, 10, TimeUnit.SECONDS);

        recoverActiveAttempt();
        recoverActiveRecordings();
        recordingExecutor.execute(this::recoverCompletedWallBatches);
        events = new EventServer(38765, this::onEvent);
        events.start();
        hermes = new HermesMonitor(HermesMonitor.defaultInstancesDirectory(), this::onEvent, this::onHermesStatus);
        hermes.start();
    }

    private static String readQrFromClipboard() throws Exception {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        String text = clipboard.getString();
        if (text != null && (text.trim().startsWith("obsws://") || text.trim().startsWith("obswss://")))
            return text.trim();
        Image image = clipboard.getImage();
        if (image == null) throw new IllegalArgumentException("A área de transferência não contém texto nem imagem de QR");
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        if (buffered == null) throw new IllegalArgumentException("Não foi possível ler a imagem da área de transferência");
        return new MultiFormatReader().decode(new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(buffered)))).getText();
    }

    private static VBox card(String title, String subtitle, javafx.scene.Node... children) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        Label description = new Label(subtitle);
        description.getStyleClass().add("muted-text");
        description.setWrapText(true);
        VBox box = new VBox(10, heading, description);
        box.getStyleClass().add("surface-card");
        box.getChildren().addAll(children);
        return box;
    }

    private static VBox card(String title, javafx.scene.Node... children) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        VBox box = new VBox(10, heading);
        box.getStyleClass().add("surface-card");
        box.getChildren().addAll(children);
        return box;
    }

    private static VBox statusCard(String title, Label... statuses) {
        VBox content = new VBox(7, statuses);
        for (Label status : statuses) {
            status.setWrapText(true);
            status.getStyleClass().add("status-line");
        }
        return card(title, content);
    }

    private void installSystemTray() {
        try {
            if (!SystemTray.isSupported()) return;
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(21, 30, 46));
            graphics.fillRoundRect(0, 0, 16, 16, 5, 5);
            graphics.setColor(new Color(239, 68, 68));
            graphics.fillOval(4, 4, 8, 8);
            graphics.dispose();

            trayIcon = new TrayIcon(image, "MCSR AutoRecorder");
            trayIcon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException | RuntimeException error) {
            trayIcon = null;
            System.err.println("System tray notifications are unavailable: " + errorMessage(error));
        }
    }

    private void removeSystemTray() {
        TrayIcon icon = trayIcon;
        trayIcon = null;
        if (icon == null) return;
        try { SystemTray.getSystemTray().remove(icon); }
        catch (RuntimeException error) {
            System.err.println("Could not remove the application tray icon: " + errorMessage(error));
        }
    }

    private void notifyRecording(String title, String message, TrayIcon.MessageType type) {
        String fullMessage = title + " — " + message;
        String timestamp = java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            if (recordingNotice != null) recordingNotice.setText("Último aviso · " + timestamp + " · " + fullMessage);
        });
        TrayIcon icon = trayIcon;
        if (icon != null) {
            String body = message.length() > 220 ? message.substring(0, 217) + "…" : message;
            icon.displayMessage(title, body, type);
        }
    }

    private static String recordingName(SourceRecordService.Handle handle) {
        return ("wall".equals(handle.stream()) ? "Ciclo da wall" : "Tentativa")
                + " · " + handle.file().getFileName();
    }

    private static VBox fieldGroup(String title, Control field) {
        Label label = new Label(title);
        label.getStyleClass().add("field-label");
        field.setMaxWidth(Double.MAX_VALUE);
        VBox group = new VBox(5, label, field);
        VBox.setVgrow(field, Priority.NEVER);
        return group;
    }

    private static VBox pageHeader() {
        Label title = new Label("MCSR AutoRecorder");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Um vídeo contínuo por ciclo da SeedQueue");
        subtitle.getStyleClass().add("page-subtitle");
        return new VBox(4, title, subtitle);
    }

    private static void styleDialog(Dialog<?> dialog) {
        var stylesheet = App.class.getResource("/dev/autorecorder/app.css");
        if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return "Sem arquivo verificado";
        try { return Path.of(path).getFileName().toString(); }
        catch (RuntimeException ignored) { return "Caminho de arquivo inválido"; }
    }

    private void showRecordingSettings(Stage owner) {
        RecorderConfig old = recorder.get();
        CheckBox autoAttempts = new CheckBox("Gravar vídeos separados por tentativa (sem modo wall)");
        autoAttempts.setSelected(old.automaticAttempts());
        CheckBox recordWall = new CheckBox("Vídeo contínuo da wall: termina no Title ou na próxima wall");
        recordWall.setSelected(old.recordWall());
        if (recordWall.isSelected()) autoAttempts.setSelected(false);
        autoAttempts.disableProperty().bind(recordWall.selectedProperty());
        recordWall.selectedProperty().addListener((observable, wasSelected, selected) -> {
            if (selected) autoAttempts.setSelected(false);
        });
        autoAttempts.selectedProperty().addListener((observable, wasSelected, selected) -> {
            if (selected) recordWall.setSelected(false);
        });
        String savedMinecraftSource = old.gameplaySource().isBlank() ? old.wallSource() : old.gameplaySource();
        TextField minecraftSource = new TextField(savedMinecraftSource);
        minecraftSource.setPromptText("Nome exato do source OBS que captura o Minecraft");
        TextField outputDirectory = new TextField(old.outputDirectory());
        TextField filenameTemplate = new TextField(old.filenameTemplate());
        filenameTemplate.setPromptText("resets #0");
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose AutoRecorder output folder");
            try {
                Path initial = Path.of(outputDirectory.getText()).toAbsolutePath().normalize();
                if (java.nio.file.Files.isDirectory(initial)) chooser.setInitialDirectory(initial.toFile());
            } catch (Exception ignored) { }
            java.io.File selected = chooser.showDialog(owner);
            if (selected != null) outputDirectory.setText(selected.toPath().toString());
        });
        Label plugin = new Label("Requer OBS WebSocket v5, OBS Source Record, SeedQueue e o State Output permitido. Use o source OBS que captura a janela inteira do Minecraft. O áudio separado usa a faixa 1 do mixer do OBS; deixe a fonte de áudio do Minecraft atribuída a essa faixa.");
        plugin.setWrapText(true);
        Label templateHelp = new Label("No modo wall, um único vídeo captura a wall e as tentativas. Ele termina quando você volta à tela inicial (Title) ou quando a próxima wall abre; não são criados vídeos separados por tentativa.");
        templateHelp.setWrapText(true);
        Label mainRecord = new Label("O vídeo começa ao abrir a wall e captura a janela inteira do Minecraft. Ele termina quando você volta à tela inicial (Title); a próxima gravação começa ao abrir a wall novamente. Se a próxima wall abrir antes, o vídeo anterior também é finalizado.");
        mainRecord.setWrapText(true);
        VBox content = new VBox(10, autoAttempts, recordWall,
                new HBox(8, new Label("Source de captura do Minecraft"), minecraftSource),
                new Label("Pasta de gravação"), new HBox(8, outputDirectory, browse),
                new Label("Modelo de nome dos vídeos"), filenameTemplate, templateHelp, plugin, mainRecord);
        content.setPadding(new Insets(16));
        Dialog<ButtonType> dialog = new Dialog<>();
        styleDialog(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Configurar gravações");
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType("Salvar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(save::equals).ifPresent(result -> {
            if ((autoAttempts.isSelected() || recordWall.isSelected()) && minecraftSource.getText().isBlank()) {
                new Alert(Alert.AlertType.WARNING, "Informe o nome exato do source OBS que captura o Minecraft.", ButtonType.OK).showAndWait();
                return;
            }
            if ((autoAttempts.isSelected() || recordWall.isSelected()) && outputDirectory.getText().isBlank()) {
                new Alert(Alert.AlertType.WARNING, "Escolha uma pasta para as gravações.", ButtonType.OK).showAndWait();
                return;
            }
            if (filenameTemplate.getText().isBlank()) {
                new Alert(Alert.AlertType.WARNING, "Informe um modelo de nome, por exemplo: resets #0.", ButtonType.OK).showAndWait();
                return;
            }
            RecorderConfig config = new RecorderConfig(autoAttempts.isSelected(), recordWall.isSelected(),
                    minecraftSource.getText(), minecraftSource.getText(), outputDirectory.getText(), filenameTemplate.getText());
            try {
                db.saveRecorderConfig(config);
                recorder.set(config);
                if (config.recordWall()) {
                    for (UUID attemptId : List.copyOf(activeGameplay.keySet())) stopGameplayRecording(attemptId);
                }
                if (!config.recordWall()) {
                    restartWallSessionId = null;
                    stopWallRecording();
                } else if (minecraftSessionActive && (wallSegmentStartedThisSession || seedQueueOnWall)) {
                    wallSegmentStartedThisSession = true;
                    if (activeWallBatchId == null) activeWallBatchId = createWallBatch();
                    startWallRecording(activeWallBatchId);
                }
                refreshRecordingLabels();
                refreshStorageStatus();
            } catch (SQLException ex) {
                new Alert(Alert.AlertType.ERROR, "Could not save recording settings: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });
    }

    private void showRetentionSettings(Stage owner) {
        RetentionConfig old = retention.get();
        CheckBox nether = new CheckBox("Manter se entrou no Nether");
        nether.setSelected(old.keepIfNether());
        CheckBox end = new CheckBox("Manter se entrou no End");
        end.setSelected(old.keepIfEnd());
        CheckBox duration = new CheckBox("Manter se a tentativa durou pelo menos:");
        duration.setSelected(old.keepIfDuration());
        int initialMinutes = (int) Math.max(1, Math.min(1440, (old.minimumDurationSeconds() + 59) / 60));
        Spinner<Integer> minutes = new Spinner<>(1, 1440, initialMinutes);
        minutes.setEditable(true);
        minutes.setPrefWidth(100);
        minutes.disableProperty().bind(duration.selectedProperty().not());
        CheckBox autoDelete = new CheckBox("Mover automaticamente para quarentena os vídeos que não atendem aos filtros");
        autoDelete.setSelected(old.automaticDeletion());
        CheckBox dryRun = new CheckBox("Simulação: mostrar decisões sem mover arquivos");
        dryRun.setSelected(old.dryRun());
        dryRun.disableProperty().bind(autoDelete.selectedProperty().not());
        Label semantics = new Label("No modo wall, cada gravação contém a wall e as tentativas até voltar à tela inicial (Title) ou abrir a próxima wall. O vídeo inteiro é mantido se qualquer tentativa vinculada chegou ao Nether; resultados desconhecidos, tentativas ativas ou marcadas como importantes/excluídas preservam o vídeo. Clipes finalizados de até 10 segundos sem tentativa vinculada podem ir para a quarentena. O app move elegíveis para quarentena; não os apaga permanentemente.");
        semantics.setWrapText(true);
        Label limits = new Label("A quarentena automática vem desligada. Ative-a e desmarque a simulação para mover os vídeos elegíveis. A regra de clipes vazios remove apenas arquivos finalizados de até 10 segundos sem tentativa associada.");
        limits.setWrapText(true);
        VBox content = new VBox(12, nether, end, new HBox(8, duration, minutes, new Label("minutes")),
                autoDelete, dryRun, new Separator(), semantics, limits);
        content.setPadding(new Insets(16));
        Dialog<ButtonType> dialog = new Dialog<>();
        styleDialog(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Filtros de retenção");
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType("Salvar filtros", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.showAndWait().filter(save::equals).ifPresent(result -> {
            RetentionConfig config = new RetentionConfig(nether.isSelected(), end.isSelected(), duration.isSelected(),
                    minutes.getValue() * 60L, autoDelete.isSelected(), dryRun.isSelected());
            try {
                db.saveRetentionConfig(config);
                retention.set(config);
            } catch (SQLException ex) {
                new Alert(Alert.AlertType.ERROR, "Não foi possível salvar as regras: " + ex.getMessage(), ButtonType.OK).showAndWait();
            }
        });
    }

    private void showAuditLog(Stage owner) {
        ListView<String> log = new ListView<>();
        Runnable refresh = () -> {
            try {
                List<AuditEntry> entries = db.recentAudit(1000);
                log.getItems().setAll(entries.stream().map(a -> a.at() + " | " + a.event()
                        + (a.attemptId() == null ? "" : " | " + a.attemptId())
                        + (a.detail() == null ? "" : " | " + a.detail())).toList());
            } catch (SQLException e) { log.getItems().setAll("Database error: " + e.getMessage()); }
        };
        refresh.run();
        Button reload = new Button("Atualizar");
        reload.setOnAction(e -> refresh.run());
        VBox content = new VBox(8, new Label("Transições de tentativas, gravações, retenção e diagnósticos"), log, reload);
        VBox.setVgrow(log, Priority.ALWAYS);
        Dialog<ButtonType> dialog = new Dialog<>();
        styleDialog(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Histórico e logs");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(900, 500);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void showRetentionPreview(Stage owner) {
        ListView<String> preview = new ListView<>();
        try {
            RetentionConfig config = retention.get();
            List<String> rows = new ArrayList<>(db.recent().stream().map(attempt -> {
                RetentionPolicy.Decision decision = retentionPolicy.evaluate(attempt, config);
                String action = !decision.wouldDelete() ? "MANTER"
                        : decision.actionAllowed() ? "ENVIAR À QUARENTENA" : "SERIA ENVIADO À QUARENTENA (simulação/desligada)";
                return action + " | " + attempt.startedAt() + " | " + attempt.worldId()
                        + " | " + (attempt.recordingPath() == null ? "sem vídeo verificado" : attempt.recordingPath())
                        + " | " + decision.reason();
            }).toList());
            Map<UUID, List<RecordingEntry>> wallBatches = db.recentRecordings().stream()
                    .filter(recording -> "wall".equals(recording.stream()))
                    .collect(java.util.stream.Collectors.groupingBy(RecordingEntry::sessionId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
            for (Map.Entry<UUID, List<RecordingEntry>> batch : wallBatches.entrySet()) {
                if (!db.wallBatchExists(batch.getKey())) continue;
                if (!db.isWallBatchComplete(batch.getKey())) {
                    for (RecordingEntry recording : batch.getValue())
                        rows.add("MANTER | lote não concluído | " + recording.path() + " | confirme ao voltar ao Title ou abrir outra wall");
                    continue;
                }
                List<Attempt> attempts = db.attemptsForWallBatch(batch.getKey());
                boolean shortEmptyClip = attempts.isEmpty() && isShortEmptyWallClip(batch.getValue());
                RetentionPolicy.Decision decision = shortEmptyClip
                        ? retentionPolicy.evaluateEmptyWallBatch(config)
                        : retentionPolicy.evaluateWallBatch(attempts, config);
                boolean outputsSettled = batch.getValue().stream().allMatch(recording ->
                        ("FINALIZED".equals(recording.status()) || "QUARANTINED".equals(recording.status())) && recording.path() != null);
                String action = !decision.wouldDelete() || !outputsSettled ? "MANTER"
                        : decision.actionAllowed() ? "ENVIAR LOTE À QUARENTENA" : "SERIA ENVIADO À QUARENTENA (simulação/desligada)";
                for (RecordingEntry recording : batch.getValue())
                    rows.add(("QUARANTINED".equals(recording.status()) ? "JÁ NA QUARENTENA" : action)
                            + " | lote " + batch.getKey() + " | " + recording.path() + " | "
                            + (outputsSettled ? decision.reason() : "há um arquivo não finalizado; preservado"));
            }
            preview.getItems().setAll(rows);
        } catch (SQLException e) {
            preview.getItems().setAll("Não foi possível calcular a prévia: " + e.getMessage());
        }
        VBox content = new VBox(8, new Label("Esta prévia não move arquivos. Cada vídeo da SeedQueue contém todas as tentativas vinculadas ao intervalo entre duas aberturas da wall."), preview);
        VBox.setVgrow(preview, Priority.ALWAYS);
        Dialog<ButtonType> dialog = new Dialog<>();
        styleDialog(dialog);
        dialog.initOwner(owner);
        dialog.setTitle("Prévia da retenção");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(900, 500);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private synchronized void onEvent(JsonNode event) {
        String type = event.path("type").asText();
        String world = event.path("worldId").asText("");
        try {
            switch (type) {
                case "SESSION_START" -> {
                    minecraftSessionActive = true;
                    seedQueueOnWall = false;
                    wallSegmentStartedThisSession = false;
                    restartWallSessionId = null;
                    if (wallHandle == null) activeWallBatchId = null;
                    if (current.get() == null) session = UUID.randomUUID();
                    Platform.runLater(() -> seedQueueStatus.setText("SeedQueue: aguardando a wall abrir"));
                    refreshRecordingLabels();
                }
                case "SEEDQUEUE_STATE" -> {
                    String state = event.path("state").asText("");
                    boolean onWall = event.path("onWall").asBoolean(false);
                    boolean wallJustOpened = onWall && !seedQueueOnWall;
                    seedQueueState = state;
                    seedQueueOnWall = onWall;
                    if (wallJustOpened && minecraftSessionActive && recorder.get().recordWall()) {
                        rotateWallRecordingAtOpen();
                    }
                    if ("title".equalsIgnoreCase(state) && !onWall && minecraftSessionActive
                            && recorder.get().recordWall() && activeWallBatchId != null
                            && wallSegmentStartedThisSession) {
                        UUID endingBatch = activeWallBatchId;
                        UUID endingGameSession = session;
                        wallSegmentStartedThisSession = false;
                        finishWallRecordingAtTitle(endingBatch, endingGameSession);
                    }
                    Platform.runLater(() -> seedQueueStatus.setText(onWall
                            ? "SeedQueue: na wall (State Output)"
                            : "SeedQueue: fora da wall (" + state + ")"));
                    refreshRecordingLabels();
                }
                case "SESSION_END" -> {
                    minecraftSessionActive = false;
                    seedQueueOnWall = false;
                    wallSegmentStartedThisSession = false;
                    restartWallSessionId = null;
                    Attempt attempt = current.getAndSet(null);
                    if (attempt != null && attempt.endedAt() == null) {
                        db.save(machine.finish(attempt, false));
                        stopGameplayRecording(attempt.id());
                    }
                    stopWallRecording();
                    activeWallBatchId = null;
                    Platform.runLater(() -> gameStatus.setText("Minecraft: session ended; interrupted attempt preserved for review"));
                    Platform.runLater(() -> seedQueueStatus.setText("SeedQueue: sessão encerrada"));
                }
                case "ATTEMPT_START" -> {
                    Attempt previous = current.get();
                    if (previous != null && previous.endedAt() == null && previous.worldId().equals(world)) return;
                    if (previous != null && previous.endedAt() == null) {
                        db.save(machine.finish(previous, false));
                        stopGameplayRecording(previous.id());
                    }
                    Attempt attempt = machine.begin(session, world);
                    current.set(attempt);
                    db.save(attempt);
                    if (activeWallBatchId != null && wallSegmentStartedThisSession) {
                        try { db.linkAttemptToWallBatch(activeWallBatchId, attempt.id()); }
                        catch (SQLException error) { auditRecordingError(attempt.id(), "Could not link attempt to SeedQueue wall batch; batch will be preserved: " + errorMessage(error)); }
                    }
                    attemptNumbers.put(attempt.id(), nextAttemptNumber++);
                    startGameplayRecording(attempt);
                    Platform.runLater(() -> {
                        attemptStatus.setText("Attempt: " + attempt.id() + " · " + attempt.state());
                        netherStatus.setText("Nether: unknown — waiting for SpeedRunIGT record");
                    });
                }
                case "DIMENSION" -> {
                    Attempt attempt = current.get();
                    if (attempt != null) {
                        Attempt updated = machine.dimension(attempt, event.path("dimension").asText());
                        current.set(updated);
                        db.save(updated);
                        if (updated.netherReached()) Platform.runLater(() -> netherStatus.setText("Nether: reached and persisted · SpeedRunIGT"));
                        else if (updated.endReached()) Platform.runLater(() -> netherStatus.setText("End: reached · Nether remains unknown"));
                    }
                }
                case "ATTEMPT_END" -> {
                    Attempt attempt = current.get();
                    if (attempt != null && (world.isBlank() || attempt.worldId().equals(world))) {
                        current.set(null);
                        Boolean netherEvidence = event.path("netherKnown").asBoolean(false)
                                ? event.path("netherReached").asBoolean(false) : null;
                        Attempt ended = machine.finish(attempt, true, netherEvidence);
                        db.save(ended);
                        stopGameplayRecording(attempt.id());
                        Platform.runLater(() -> {
                            attemptStatus.setText("Attempt: " + ended.state());
                            netherStatus.setText(ended.netherReached() ? "Nether: reached" : "Nether: unknown — preserved for review");
                        });
                    }
                }
                default -> { }
            }
        } catch (Exception e) {
            System.err.println("Event processing failed: " + e.getMessage());
        }
        if (historyView != null) Platform.runLater(() -> loadHistory(historyView));
    }

    private void startGameplayRecording(Attempt attempt) {
        RecorderConfig config = recorder.get();
        if (config.recordWall() || !config.automaticAttempts() || activeGameplay.containsKey(attempt.id()) || !startingGameplay.add(attempt.id())) return;
        long attemptNumber = attemptNumbers.computeIfAbsent(attempt.id(), ignored -> Math.max(0L, nextAttemptNumber - 1));
        String filename = recordingFilename(config.filenameTemplate(), attemptNumber);
        refreshRecordingLabels("starting attempt " + attempt.id());
        recordingExecutor.execute(() -> {
            try {
                SourceRecordService.Handle handle = sourceRecord.start(attempt.sessionId(), attempt.id(), "gameplay",
                        config.gameplaySource(), config.outputDirectory(), filename, prepared -> {
                            try { db.saveRecording(entry(prepared, null, "STARTING", prepared.file().toString(), null)); }
                            catch (SQLException e) { throw new IllegalStateException("Could not save recording metadata", e); }
                        });
                activeGameplay.put(attempt.id(), handle);
                db.saveRecording(entry(handle, null, "RECORDING", handle.file().toString(), null));
                notifyRecording("Gravação iniciada", recordingName(handle), TrayIcon.MessageType.INFO);
                db.updateAttemptRecordingPath(attempt.id(), handle.file().toString());
                current.updateAndGet(value -> value != null && value.id().equals(attempt.id())
                        ? value.withRecordingPath(handle.file().toString()) : value);
                refreshRecordingLabels("recording attempt " + attempt.id());
                refreshHistoryLater();
            } catch (Exception error) {
                String message = errorMessage(error);
                if (error instanceof SourceRecordService.StartFailure failure) {
                    SourceRecordService.Handle uncertain = failure.handle();
                    if (!failure.stopConfirmed()) message += "; OBS has not confirmed stop; a retry is queued";
                    try { db.saveRecording(entry(uncertain, null, "NEEDS_REVIEW", uncertain.file().toString(), message)); }
                    catch (SQLException e) { message += " (recording metadata update failed: " + e.getMessage() + ")"; }
                    activeGameplay.put(attempt.id(), uncertain);
                    pendingGameplayStops.add(attempt.id());
                }
                String failureMessage = message;
                auditRecordingError(attempt.id(), "gameplay start failed: " + failureMessage);
                notifyRecording("Falha ao iniciar gravação", "Tentativa " + attempt.id() + " · " + failureMessage,
                        TrayIcon.MessageType.ERROR);
                if (error instanceof SourceRecordService.StartFailure) stopGameplayRecording(attempt.id());
                current.updateAndGet(value -> value != null && value.id().equals(attempt.id())
                        ? value.withError("Gameplay recording could not be verified: " + failureMessage) : value);
                refreshRecordingLabels("start failed — preserved for review");
            } finally {
                startingGameplay.remove(attempt.id());
            }
        });
    }

    private void stopGameplayRecording(UUID attemptId) {
        pendingGameplayStops.add(attemptId);
        if (!stoppingGameplay.add(attemptId)) return;
        recordingExecutor.execute(() -> {
            try {
                SourceRecordService.Handle handle = activeGameplay.get(attemptId);
                if (handle == null) {
                    pendingGameplayStops.remove(attemptId);
                    return; // A queued start, if any, is ahead of this task in the single-thread executor.
                }
                db.saveRecording(entry(handle, null, "FINALIZING", handle.file().toString(), null));
                Path finalized = sourceRecord.stop(handle);
                db.saveRecording(entry(handle, Instant.now(), "FINALIZED", finalized.toString(), null));
                activeGameplay.remove(attemptId, handle);
                notifyRecording("Gravação finalizada", recordingName(handle), TrayIcon.MessageType.INFO);
                applyRetention(attemptId, finalized);
                refreshHistoryLater();
                refreshStorageStatus();
                pendingGameplayStops.remove(attemptId);
                refreshRecordingLabels("attempt file finalized");
                if (!shuttingDown && recorder.get().automaticAttempts() && !recorder.get().recordWall()) {
                    Attempt stillActive = db.recent().stream().filter(a -> a.id().equals(attemptId))
                            .findFirst().filter(a -> a.endedAt() == null).orElse(null);
                    if (stillActive != null) startGameplayRecording(stillActive);
                }
            } catch (Exception error) {
                String message = errorMessage(error);
                try {
                    SourceRecordService.Handle handle = activeGameplay.get(attemptId);
                    if (handle != null) db.saveRecording(entry(handle, null, "NEEDS_REVIEW", handle.file().toString(), message));
                    auditRecordingError(attemptId, "gameplay stop/finalize failed: " + message);
                } catch (Exception ignored) { }
                notifyRecording("Falha ao finalizar gravação", "Tentativa " + attemptId + " · " + message,
                        TrayIcon.MessageType.ERROR);
                refreshRecordingLabels("finalization uncertain — preserved for review");
                refreshHistoryLater();
            } finally {
                stoppingGameplay.remove(attemptId);
            }
        });
    }

    private void rotateWallRecordingAtOpen() {
        wallSegmentStartedThisSession = true;
        UUID previousBatch = activeWallBatchId;
        if (previousBatch != null) {
            try { db.completeWallBatch(previousBatch); }
            catch (SQLException error) {
                auditRecordingError(null, "Could not mark previous SeedQueue clip complete; it will be preserved: " + errorMessage(error));
            }
        }

        UUID nextBatch = createWallBatch();
        activeWallBatchId = nextBatch;
        boolean previousOutputNeedsStop = wallHandle != null || wallStartQueued || wallStopPending || wallStopQueued;
        if (previousOutputNeedsStop) {
            restartWallSessionId = nextBatch;
            stopWallRecording();
        } else {
            startWallRecording(nextBatch);
        }
    }

    /**
     * Title screen ends the current capture cycle. A short delay lets Hermes
     * consume the world-leave log and SpeedRunIGT record before we close the
     * wall batch, so retention sees the final confirmed attempt outcomes.
     */
    private void finishWallRecordingAtTitle(UUID batchId, UUID gameSessionId) {
        CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS).execute(() -> {
            synchronized (App.this) {
                if (shuttingDown || !minecraftSessionActive || seedQueueOnWall
                        || !"title".equalsIgnoreCase(seedQueueState)
                        || !gameSessionId.equals(session)
                        || !batchId.equals(activeWallBatchId)) return;

                Attempt attempt = current.getAndSet(null);
                if (attempt != null && attempt.endedAt() == null) {
                    // A missing world-leave result is ambiguous. Preserve it for
                    // review instead of treating Title as proof of no Nether.
                    try { db.save(machine.finish(attempt, false)); }
                    catch (SQLException error) {
                        auditRecordingError(attempt.id(), "Could not persist attempt ending at Title; related wall video will be preserved: " + errorMessage(error));
                    }
                    stopGameplayRecording(attempt.id());
                }

                try {
                    db.completeWallBatch(batchId);
                } catch (SQLException error) {
                    auditRecordingError(null, "Could not mark SeedQueue batch complete at Title; video will be preserved: " + errorMessage(error));
                }
                refreshRecordingLabels("Title detected; finalizing the current SeedQueue video");
                stopWallRecording();
            }
        });
    }

    private UUID createWallBatch() {
        UUID id = UUID.randomUUID();
        try {
            db.beginWallBatch(id);
        } catch (SQLException error) {
            auditRecordingError(null, "Could not save SeedQueue batch metadata: " + errorMessage(error));
            refreshRecordingLabels("batch metadata unavailable; recording will be preserved for review");
        }
        return id;
    }

    private void startWallRecording(UUID batchId) {
        RecorderConfig config = recorder.get();
        if (!config.recordWall() || !minecraftSessionActive) return;
        UUID recordingBatchId = batchId;
        try {
            if (db.isWallBatchComplete(batchId)) {
                recordingBatchId = createWallBatch();
                if (batchId.equals(activeWallBatchId)) activeWallBatchId = recordingBatchId;
            }
        } catch (SQLException error) {
            auditRecordingError(null, "Could not check SeedQueue batch state; recording was not restarted: " + errorMessage(error));
            refreshRecordingLabels("batch state could not be checked");
            return;
        }
        final UUID batchForRecording = recordingBatchId;
        if (config.wallSource().isBlank() || config.outputDirectory().isBlank()) {
            refreshRecordingLabels("configure a Minecraft source and output folder to record the wall");
            return;
        }
        if (wallStopPending || wallStopQueued) {
            restartWallSessionId = batchForRecording;
            return;
        }
        if (wallHandle != null || wallStartQueued) return;
        wallStartQueued = true;
        String filename = recordingFilename(config.filenameTemplate(), nextAttemptNumber);
        refreshRecordingLabels("iniciando gravação da fila SeedQueue");
        recordingExecutor.execute(() -> {
            try {
                if (wallHandle != null) return;
                SourceRecordService.Handle handle = sourceRecord.start(batchForRecording, null, "wall",
                        config.wallSource(), config.outputDirectory(), filename, prepared -> {
                            try { db.saveRecording(entry(prepared, null, "STARTING", prepared.file().toString(), null)); }
                            catch (SQLException e) { throw new IllegalStateException("Could not save recording metadata", e); }
                        });
                wallHandle = handle;
                db.saveRecording(entry(handle, null, "RECORDING", handle.file().toString(), null));
                notifyRecording("Gravação iniciada", recordingName(handle), TrayIcon.MessageType.INFO);
                refreshRecordingLabels("trecho da fila SeedQueue está gravando");
            } catch (Exception error) {
                String message = errorMessage(error);
                if (error instanceof SourceRecordService.StartFailure failure) {
                    SourceRecordService.Handle uncertain = failure.handle();
                    if (!failure.stopConfirmed()) message += "; OBS has not confirmed stop; a retry is queued";
                    else message += "; OBS confirmed stop, but this wall clip did not start reliably";
                    try { db.saveRecording(entry(uncertain, null, "NEEDS_REVIEW", uncertain.file().toString(), message)); }
                    catch (SQLException e) { message += " (recording metadata update failed: " + e.getMessage() + ")"; }
                    if (failure.stopConfirmed()) {
                        wallHandle = null;
                        wallStopPending = false;
                    } else {
                        wallHandle = uncertain;
                        wallStopPending = true;
                    }
                }
                auditRecordingError(null, "SeedQueue wall start failed: " + message);
                notifyRecording("Falha ao iniciar gravação", "Ciclo da wall · " + message, TrayIcon.MessageType.ERROR);
                if (error instanceof SourceRecordService.StartFailure failure && !failure.stopConfirmed())
                    stopWallRecording();
                refreshRecordingLabels("wall start failed — check OBS Source Record");
            } finally {
                wallStartQueued = false;
            }
        });
    }

    private void stopWallRecording() {
        if ((!wallStartQueued && wallHandle == null && !wallStopPending) || wallStopQueued) return;
        wallStopQueued = true;
        recordingExecutor.execute(() -> {
            boolean continueWithNextSegment = false;
            try {
                SourceRecordService.Handle handle = wallHandle;
                if (handle == null) {
                    wallStopPending = false;
                    continueWithNextSegment = true;
                    return;
                }
                db.saveRecording(entry(handle, null, "FINALIZING", handle.file().toString(), null));
                Path finalized = sourceRecord.stop(handle);
                RecordingEntry finalizedEntry = entry(handle, Instant.now(), "FINALIZED", finalized.toString(), null);
                db.saveRecording(finalizedEntry);
                wallHandle = null;
                wallStopPending = false;
                notifyRecording("Gravação finalizada", recordingName(handle), TrayIcon.MessageType.INFO);
                String retentionNote = applyWallBatchRetention(handle.sessionId());
                if (handle.sessionId().equals(activeWallBatchId)) activeWallBatchId = null;
                refreshRecordingLabels("SeedQueue batch finalized; " + retentionNote);
                continueWithNextSegment = true;
            } catch (Exception error) {
                String message = errorMessage(error);
                boolean outputIsGone = error instanceof SourceRecordService.OutputMissingException;
                try {
                    SourceRecordService.Handle handle = wallHandle;
                    if (handle != null) db.saveRecording(entry(handle, null, "NEEDS_REVIEW", handle.file().toString(), message));
                    auditRecordingError(null, "SeedQueue wall stop/finalize failed: " + message);
                } catch (Exception ignored) { }
                if (outputIsGone) {
                    // Keep the missing clip in history for review, but do not let
                    // its vanished OBS filter block the next wall's video.
                    wallHandle = null;
                    wallStopPending = false;
                    continueWithNextSegment = true;
                } else {
                    wallStopPending = true;
                }
                notifyRecording("Falha ao finalizar gravação", "Ciclo da wall · " + message
                        + " · vídeo preservado para revisão", TrayIcon.MessageType.ERROR);
                refreshRecordingLabels(outputIsGone
                        ? "Gravacao anterior ausente; salva para revisao. A proxima wall pode gravar normalmente."
                        : "wall: erro do Source Record: " + message
                            + " - clipe preservado; reconecte o OBS para tentar recuperar");
            } finally {
                wallStopQueued = false;
                if (continueWithNextSegment) startNextWallSession();
            }
        });
    }

    private void retryPendingStops() {
        if (!obs.isConnected()) return;
        for (UUID attemptId : List.copyOf(pendingGameplayStops)) stopGameplayRecording(attemptId);
        if (wallStopPending) stopWallRecording();
    }

    private void onObsConnected() {
        sourceRecord.isAvailable().whenComplete((available, error) -> Platform.runLater(() -> {
            if (error != null)
                sourceRecordStatus.setText("OBS Source Record: check failed — " + errorMessage(error));
            else if (!Boolean.TRUE.equals(available))
                sourceRecordStatus.setText("OBS Source Record: vendor response was unexpected — reconnect OBS after enabling the plugin");
            else sourceRecordStatus.setText("OBS Source Record: available");
        }));
        retryPendingStops();
        Attempt attempt = current.get();
        if (attempt != null) startGameplayRecording(attempt);
        if (minecraftSessionActive && (wallSegmentStartedThisSession || seedQueueOnWall)
                && recorder.get().recordWall()) {
            wallSegmentStartedThisSession = true;
            if (activeWallBatchId == null) activeWallBatchId = createWallBatch();
            startWallRecording(activeWallBatchId);
        }
    }

    private void startNextWallSession() {
        UUID nextSession = restartWallSessionId;
        restartWallSessionId = null;
        if (!shuttingDown && minecraftSessionActive && wallSegmentStartedThisSession
                && nextSession != null && recorder.get().recordWall())
            startWallRecording(nextSession);
    }

    private void recoverActiveRecordings() {
        try {
            List<Attempt> attempts = db.recent();
            for (RecordingEntry entry : db.recentRecordings()) {
                if (!Set.of("STARTING", "RECORDING", "FINALIZING", "NEEDS_REVIEW").contains(entry.status())) continue;
                if (entry.path() == null) {
                    db.saveRecording(entry.withState("NEEDS_REVIEW", null, null, "No output path was recorded before shutdown"));
                    continue;
                }
                Path file = Path.of(entry.path()).toAbsolutePath().normalize();
                Path directory = file.getParent();
                if (directory == null) {
                    db.saveRecording(entry.withState("NEEDS_REVIEW", null, entry.path(), "Output path has no parent folder"));
                    continue;
                }
                SourceRecordService.Handle handle = new SourceRecordService.Handle(entry.id(), entry.sessionId(),
                        entry.attemptId(), entry.stream(), entry.sourceName(), entry.filterName(), entry.startedAt(), file, directory);
                if ("wall".equals(entry.stream())) {
                    if (wallHandle == null) {
                        wallHandle = handle;
                        if (db.wallBatchExists(entry.sessionId())) {
                            activeWallBatchId = entry.sessionId();
                            if (db.isWallBatchComplete(entry.sessionId())) wallStopPending = true;
                        } else {
                            // Older recordings used the Minecraft session id, which may cover
                            // several batches. Finalize them conservatively without grouping them.
                            activeWallBatchId = null;
                            wallStopPending = true;
                        }
                        if ("STARTING".equals(entry.status()) || "FINALIZING".equals(entry.status()) || "NEEDS_REVIEW".equals(entry.status()))
                            wallStopPending = true;
                    }
                } else if (entry.attemptId() != null) {
                    activeGameplay.putIfAbsent(entry.attemptId(), handle);
                    if (recorder.get().recordWall() || "STARTING".equals(entry.status())
                            || "FINALIZING".equals(entry.status()) || "NEEDS_REVIEW".equals(entry.status()))
                        pendingGameplayStops.add(entry.attemptId());
                    else if (attempts.stream().filter(a -> a.id().equals(entry.attemptId()))
                            .findFirst().map(a -> a.endedAt() != null).orElse(true))
                        pendingGameplayStops.add(entry.attemptId());
                }
            }
            refreshRecordingLabels("recovered saved recording state");
        } catch (Exception e) {
            System.err.println("Could not recover recording state: " + errorMessage(e));
        }
    }

    private RecordingEntry entry(SourceRecordService.Handle handle, Instant ended, String status, String path, String error) {
        long size = 0;
        try { if (path != null) size = java.nio.file.Files.size(Path.of(path)); }
        catch (Exception ignored) { }
        return new RecordingEntry(handle.id(), handle.sessionId(), handle.attemptId(), handle.stream(),
                handle.sourceName(), handle.filterName(), handle.startedAt(), ended, path, status, error, size);
    }

    private void auditRecordingError(UUID attemptId, String message) {
        try { db.audit(attemptId, "RECORDING_ERROR", message); }
        catch (SQLException e) { System.err.println("Could not write recording diagnostic: " + e.getMessage()); }
    }

    private void applyRetention(UUID attemptId, Path finalized) {
        Path moved = null;
        try {
            Attempt attempt = db.recent().stream().filter(a -> a.id().equals(attemptId)).findFirst().orElse(null);
            if (attempt == null) {
                auditRecordingError(attemptId, "retention skipped: attempt metadata is missing; recording preserved");
                return;
            }
            RetentionPolicy.Decision decision = retentionPolicy.evaluate(
                    attempt.withRecordingPath(finalized.toString()), retention.get());
            db.updateAttemptRetentionDecision(attemptId, decision.reason());
            if (!decision.wouldDelete() || !decision.actionAllowed()) return;

            for (RecordingEntry recording : db.recordingsForAttempt(attemptId)) {
                if (!"FINALIZED".equals(recording.status()) || recording.path() == null) continue;
                Path source = Path.of(recording.path()).toAbsolutePath().normalize();
                moved = quarantine.quarantine(source, recorder.get().outputDirectory(), attemptId);
                db.saveRecording(recording.withState("QUARANTINED", recording.endedAt(), moved.toString(), decision.reason()));
                if (recording.path().equals(attempt.recordingPath()))
                    db.updateAttemptRecordingPath(attemptId, moved.toString());
                db.audit(attemptId, "QUARANTINE", moved.toString() + " | " + decision.reason());
            }
            if (moved == null) throw new java.io.IOException("No finalized gameplay recording row was eligible to quarantine");
            db.updateAttemptRetentionDecision(attemptId, "QUARANTINED: " + decision.reason());
            db.updateAttemptState(attemptId, "QUARANTINED");
            refreshRecordingLabels("eligible attempt moved to quarantine");
            refreshStorageStatus();
            refreshHistoryLater();
        } catch (Exception error) {
            String message = moved == null
                    ? "retention/quarantine failed; video remains in its recorded location for review: " + errorMessage(error)
                    : "file moved to quarantine at " + moved + " but metadata needs recovery: " + errorMessage(error);
            try {
                db.updateAttemptRetentionDecision(attemptId, message);
                db.audit(attemptId, "RETENTION_ERROR", message);
            } catch (SQLException ignored) { }
            refreshRecordingLabels("retention needs review");
            refreshHistoryLater();
        }
    }

    private String applyWallBatchRetention(UUID batchId) {
        Path lastMoved = null;
        try {
            if (!db.isWallBatchComplete(batchId)) {
                db.updateWallBatchDecision(batchId, "batch not confirmed complete; video preserved");
                return "batch not marked complete; video preserved";
            }
            List<Attempt> attempts = db.attemptsForWallBatch(batchId);
            List<RecordingEntry> recordings = db.wallRecordingsForBatch(batchId);
            RetentionPolicy.Decision decision = attempts.isEmpty() && isShortEmptyWallClip(recordings)
                    ? retentionPolicy.evaluateEmptyWallBatch(retention.get())
                    : retentionPolicy.evaluateWallBatch(attempts, retention.get());
            db.updateWallBatchDecision(batchId, decision.reason());
            if (!decision.wouldDelete()) return "kept: " + decision.reason();
            if (!decision.actionAllowed()) return "eligible, but automatic quarantine is disabled or dry-run is on";
            if (recordings.isEmpty()) {
                db.updateWallBatchDecision(batchId, "no verified video files exist; batch preserved for review");
                return "no verified video found; preserved for review";
            }
            if (recordings.stream().anyMatch(r -> !"QUARANTINED".equals(r.status())
                    && (!"FINALIZED".equals(r.status()) || r.path() == null))) {
                db.updateWallBatchDecision(batchId, "at least one video is not finalized; batch preserved until recovery");
                return "a video is still finalizing; preserved until recovery";
            }

            int movedCount = 0;
            for (RecordingEntry recording : recordings) {
                if ("QUARANTINED".equals(recording.status())) continue;
                Path source = Path.of(recording.path()).toAbsolutePath().normalize();
                lastMoved = quarantine.quarantine(source, recorder.get().outputDirectory(), batchId);
                db.saveRecording(recording.withState("QUARANTINED", recording.endedAt(), lastMoved.toString(), decision.reason()));
                movedCount++;
            }
            db.updateWallBatchDecision(batchId, "QUARANTINED: " + decision.reason());
            db.audit(null, "WALL_BATCH_QUARANTINE", batchId + " | " + movedCount + " file(s) newly moved; total files " + recordings.size());
            refreshStorageStatus();
            refreshHistoryLater();
            return "moved to quarantine (" + recordings.size() + " file(s))";
        } catch (Exception error) {
            String message = lastMoved == null
                    ? "wall batch retention/quarantine failed; video preserved for review: " + errorMessage(error)
                    : "wall batch quarantine partially completed; last moved file: " + lastMoved + "; review metadata: " + errorMessage(error);
            try {
                db.updateWallBatchDecision(batchId, message);
                db.audit(null, "WALL_BATCH_RETENTION_ERROR", batchId + " | " + message);
            } catch (SQLException ignored) { }
            refreshRecordingLabels("batch preserved for review");
            return "preserved for review: " + errorMessage(error);
        }
    }

    private static boolean isShortEmptyWallClip(List<RecordingEntry> recordings) {
        if (recordings == null || recordings.isEmpty()) return false;
        Duration combined = Duration.ZERO;
        for (RecordingEntry recording : recordings) {
            if (!Set.of("FINALIZED", "QUARANTINED").contains(recording.status())
                    || recording.path() == null || recording.endedAt() == null || recording.fileSize() <= 0)
                return false;
            Duration segment = Duration.between(recording.startedAt(), recording.endedAt());
            if (segment.isNegative()) return false;
            combined = combined.plus(segment);
        }
        return !combined.isZero() && combined.compareTo(SHORT_EMPTY_WALL_CLIP_MAX_DURATION) <= 0;
    }

    private void recoverCompletedWallBatches() {
        try {
            Map<UUID, List<RecordingEntry>> batches = db.recentRecordings().stream()
                    .filter(recording -> "wall".equals(recording.stream()))
                    .collect(java.util.stream.Collectors.groupingBy(RecordingEntry::sessionId));
            for (Map.Entry<UUID, List<RecordingEntry>> batch : batches.entrySet()) {
                if (!db.wallBatchExists(batch.getKey()) || !db.isWallBatchComplete(batch.getKey())) continue;
                boolean settled = batch.getValue().stream().allMatch(recording ->
                        "FINALIZED".equals(recording.status()) || "QUARANTINED".equals(recording.status()));
                if (settled) applyWallBatchRetention(batch.getKey());
            }
        } catch (Exception error) {
            System.err.println("Could not recover completed SeedQueue batch retention: " + errorMessage(error));
        }
    }

    private void refreshHistoryLater() {
        ListView<Attempt> list = historyView;
        if (list != null) Platform.runLater(() -> loadHistory(list));
    }

    private static String errorMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String recordingFilename(String template, long attemptNumber) {
        String number = Long.toString(Math.max(0L, attemptNumber));
        if (template.contains("{attempt}")) return template.replace("{attempt}", number);
        if (template.contains("#0")) return template.replace("#0", "#" + number);
        return template + " #" + number;
    }

    private void refreshRecordingLabels() { refreshRecordingLabels(null); }

    private void refreshRecordingLabels(String note) {
        RecorderConfig config = recorder.get();
        String gameplay = config.recordWall() ? "Tentativas: incluídas no vídeo contínuo da wall"
                : !config.automaticAttempts() ? "Tentativas: gravação automática desligada"
                : "Tentativas: " + activeGameplay.size() + " gravação(ões) ativa(s)";
        String wall = !config.recordWall() && wallHandle == null ? "Gravação SeedQueue: desativada nas configurações"
                : wallStopPending ? "Gravação SeedQueue: finalização precisa ser recuperada no OBS"
                : wallHandle != null ? "Gravação SeedQueue: clipe ativo; termina no Title ou na próxima wall"
                : wallStartQueued ? "Gravação SeedQueue: iniciando trecho..."
                : config.wallSource().isBlank() || config.outputDirectory().isBlank()
                    ? "Gravação SeedQueue: configure o source e a pasta"
                : !minecraftSessionActive ? "Gravação SeedQueue: aguardando Minecraft"
                : !wallSegmentStartedThisSession ? "Gravação SeedQueue: aguardando a primeira abertura da wall"
                : seedQueueOnWall ? "Gravação SeedQueue: wall detectada; verificando o OBS"
                : "Gravação SeedQueue: aguardando a próxima wall para iniciar outro clipe";
        if (note != null && !note.isBlank()) {
            String normalizedNote = note.toLowerCase(java.util.Locale.ROOT);
            if (normalizedNote.contains("wall") || normalizedNote.contains("minecraft view")
                    || normalizedNote.contains("seedqueue") || normalizedNote.contains("fila")) wall += " · " + note;
            else gameplay += " · " + note;
        }
        String gameplayText = gameplay;
        String wallText = wall;
        Platform.runLater(() -> {
            if (gameplayStatus != null) gameplayStatus.setText(gameplayText);
            if (wallStatus != null) wallStatus.setText(wallText);
        });
    }

    private void refreshStorageStatus() {
        Label label = storageStatus;
        if (label == null) return;
        String message;
        try {
            Path root = Path.of(recorder.get().outputDirectory());
            StorageMonitor.Snapshot snapshot = storageMonitor.read(root);
            message = "Uso de gravações: " + formatBytes(snapshot.managedBytes()) + " · "
                    + formatBytes(snapshot.availableBytes()) + " livres na unidade";
        } catch (Exception e) {
            message = "Armazenamento indisponível — " + errorMessage(e);
        }
        String display = message;
        Platform.runLater(() -> label.setText(display));
    }

    private void onHermesStatus(String status) {
        Platform.runLater(() -> gameStatus.setText(status));
        if (status.startsWith("Minecraft: waiting for an approved Hermes instance")) {
            minecraftSessionActive = false;
            seedQueueOnWall = false;
            wallSegmentStartedThisSession = false;
            restartWallSessionId = null;
            Attempt attempt = current.getAndSet(null);
            if (attempt != null && attempt.endedAt() == null) {
                try {
                    db.save(machine.finish(attempt, false));
                    stopGameplayRecording(attempt.id());
                }
                catch (SQLException e) { System.err.println("Could not preserve interrupted attempt: " + e.getMessage()); }
            }
            stopWallRecording();
            activeWallBatchId = null;
            refreshRecordingLabels();
        }
    }

    private void recoverActiveAttempt() {
        try {
            db.recent().stream().filter(a -> a.endedAt() == null)
                    .max(Comparator.comparing(Attempt::startedAt)).ifPresent(attempt -> {
                        current.set(attempt);
                        session = attempt.sessionId();
                        attemptNumbers.put(attempt.id(), Math.max(0L, nextAttemptNumber - 1));
                        attemptStatus.setText("Attempt recovered: " + attempt.id() + " · awaiting game state");
                        netherStatus.setText(attempt.netherReached()
                                ? "Nether: reached and persisted" : "Nether: unknown — preserve for review");
                    });
        } catch (SQLException e) {
            System.err.println("Could not recover active attempt: " + e.getMessage());
        }
    }

    private void loadHistory(ListView<Attempt> list) {
        try {
            latestRecordingByAttempt = db.recentRecordings().stream().filter(r -> r.attemptId() != null)
                    .collect(java.util.stream.Collectors.toMap(RecordingEntry::attemptId, r -> r, (first, ignored) -> first));
            list.getItems().setAll(db.recent());
            list.refresh();
        } catch (SQLException e) { list.getItems().clear(); System.err.println("Could not load attempt history: " + e.getMessage()); }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
    }

    private void toggleSelectedFlag(ListView<Attempt> list, boolean importantFlag) {
        Attempt selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try {
            db.updateAttemptFlags(selected.id(), importantFlag ? !selected.important() : selected.important(),
                    importantFlag ? selected.excluded() : !selected.excluded());
            loadHistory(list);
        } catch (SQLException e) {
            new Alert(Alert.AlertType.ERROR, "Could not save this manual override: " + e.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    private void openSelectedRecording(ListView<Attempt> list, boolean folder) {
        Attempt selected = list.getSelectionModel().getSelectedItem();
        if (selected == null || selected.recordingPath() == null) {
            new Alert(Alert.AlertType.INFORMATION, "This attempt has no verified recording file.", ButtonType.OK).showAndWait();
            return;
        }
        try {
            Path file = Path.of(selected.recordingPath()).toAbsolutePath().normalize();
            Path target = folder ? file.getParent() : file;
            if (target == null || !java.nio.file.Files.exists(target)) throw new java.io.IOException("The recording path no longer exists");
            Desktop.getDesktop().open(target.toFile());
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, "Could not open the recording: " + e.getMessage(), ButtonType.OK).showAndWait();
        }
    }

    @Override public void stop() throws Exception {
        shuttingDown = true;
        removeSystemTray();
        if (hermes != null) hermes.close();
        if (events != null) events.close();
        Attempt attempt = current.getAndSet(null);
        if (attempt != null && attempt.endedAt() == null) {
            try { db.save(machine.finish(attempt, false)); }
            catch (SQLException e) { System.err.println("Could not save shutdown attempt state: " + e.getMessage()); }
            stopGameplayRecording(attempt.id());
        }
        for (UUID id : List.copyOf(activeGameplay.keySet())) stopGameplayRecording(id);
        stopWallRecording();
        recordingExecutor.shutdown();
        try {
            if (!recordingExecutor.awaitTermination(35, TimeUnit.SECONDS)) recordingExecutor.shutdownNow();
        } catch (InterruptedException e) {
            recordingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        obs.close();
        storageExecutor.shutdownNow();
        if (db != null) db.close();
    }

    public static void main(String[] args) { launch(args); }
}
