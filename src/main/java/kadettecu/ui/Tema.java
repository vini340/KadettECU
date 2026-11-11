package kadettecu.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.TextAttribute;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * O "visual" do programa: paleta, fontes e pequenas ferramentas de desenho.
 *
 * <p>A aparência segue as telas de referência (painel de bordo azul-escuro, molduras de aço,
 * células azul-claras, verde neon, caixa clara para o gráfico 3D). Fica tudo aqui, num lugar só,
 * para que mudar uma cor mude o programa inteiro: nenhum outro arquivo escreve um número de cor
 * "solto" que já exista nesta paleta.
 */
public final class Tema {

    // ---------------------------------------------------------------- paleta
    public static final Color FUNDO = new Color(0x050A12);
    public static final Color FUNDO_PAINEL = new Color(0x0A1424);
    public static final Color FUNDO_CAMPO = new Color(0x07111F);
    public static final Color FUNDO_ELEVADO = new Color(0x0F2038);
    public static final Color MOLDURA = new Color(0x4F7FA8);
    public static final Color MOLDURA_CLARA = new Color(0x9CC3E6);
    public static final Color MOLDURA_ESCURA = new Color(0x1F3A57);
    public static final Color CELULA = new Color(0x9CC3E6);
    public static final Color CELULA_CLARA = new Color(0xC9E0F5);
    public static final Color CELULA_ESCURA = new Color(0x6F9CC6);
    public static final Color VERDE = new Color(0x39FF6A);
    public static final Color VERDE_ESCURO = new Color(0x123D22);
    public static final Color VERDE_PRONTO = new Color(0x38D24E);
    public static final Color CIANO = new Color(0x27D3F5);
    public static final Color AMBAR = new Color(0xFFB000);
    public static final Color LARANJA = new Color(0xFF7A1A);
    public static final Color VERMELHO = new Color(0xFF3B30);
    public static final Color AZUL = new Color(0x2F6BFF);
    public static final Color PAINEL_CLARO = new Color(0xDDE7F0);
    public static final Color TEXTO = new Color(0xCFE6FF);
    public static final Color TEXTO_FRACO = new Color(0x6E8CA8);
    public static final Color DESABILITADO = new Color(0x3F566D);

    private static final String FAMILIA_MONO = escolherFamilia("Consolas", "DejaVu Sans Mono", "Menlo", "Monospaced");
    private static final String FAMILIA_UI = escolherFamilia("Segoe UI", "DejaVu Sans", "Helvetica Neue", "SansSerif");

    private Tema() {
    }

    private static String escolherFamilia(String... candidatas) {
        Set<String> instaladas = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String nome : candidatas) {
            if (instaladas.contains(nome)) {
                return nome;
            }
        }
        return candidatas[candidatas.length - 1];
    }

    // ---------------------------------------------------------------- fontes

    /** Fonte de largura fixa, para números (os dígitos não "dançam" quando o valor muda). */
    public static Font fonteMono(int estilo, float tamanho) {
        return new Font(FAMILIA_MONO, estilo, 12).deriveFont(tamanho);
    }

    /** Fonte de texto comum da interface. */
    public static Font fonteUI(int estilo, float tamanho) {
        return new Font(FAMILIA_UI, estilo, 12).deriveFont(tamanho);
    }

    /** Título em negrito com as letras um pouco afastadas, como nas telas de referência. */
    public static Font fonteTitulo(float tamanho) {
        Map<TextAttribute, Object> atributos = new HashMap<>();
        atributos.put(TextAttribute.FAMILY, FAMILIA_UI);
        atributos.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD);
        atributos.put(TextAttribute.SIZE, tamanho);
        atributos.put(TextAttribute.TRACKING, 0.08f);
        return new Font(atributos);
    }

    // ---------------------------------------------------------------- desenho

    public static void suavizar(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    public static Color comAlfa(Color cor, int alfa) {
        return new Color(cor.getRed(), cor.getGreen(), cor.getBlue(), Math.max(0, Math.min(255, alfa)));
    }

    /** Mistura duas cores: t = 0 devolve a, t = 1 devolve b. */
    public static Color misturar(Color a, Color b, double t) {
        double k = Math.max(0, Math.min(1, t));
        return new Color(
                (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * k),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }

    public static Color clarear(Color cor, double fracao) {
        return misturar(cor, Color.WHITE, fracao);
    }

    public static Color escurecer(Color cor, double fracao) {
        return misturar(cor, Color.BLACK, fracao);
    }

    /** Traço com "brilho" neon: três camadas largas e transparentes por baixo de um traço firme. */
    public static void brilho(Graphics2D g, Shape forma, Color cor, float largura) {
        Stroke antigo = g.getStroke();
        final float[] extra = {7f, 4.5f, 2.2f};
        final int[] alfa = {26, 46, 90};
        for (int i = 0; i < extra.length; i++) {
            g.setColor(comAlfa(cor, alfa[i]));
            g.setStroke(new BasicStroke(largura + extra[i], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(forma);
        }
        g.setColor(cor);
        g.setStroke(new BasicStroke(largura, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(forma);
        g.setStroke(antigo);
    }

    /** Pinta usando uma transparência temporária e devolve a anterior. */
    public static java.awt.Composite transparencia(Graphics2D g, float alfa) {
        java.awt.Composite antigo = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alfa))));
        return antigo;
    }

    /** Moldura com a aba de título azul-clara (o estilo dos "STAGE 1/2/3" da referência). */
    public static MolduraTech moldura(String titulo) {
        return new MolduraTech(titulo);
    }

    // ---------------------------------------------------------------- instalação no Swing

    /**
     * Liga o visual no Swing. Deve ser chamado uma vez, antes de criar qualquer janela.
     * Usa o Metal (que vem em todo JDK) com um tema escuro e troca os componentes mais
     * visíveis (botões, abas, listas suspensas, barras de rolagem, deslizantes) pelos
     * desenhos de {@link TemaUI}.
     */
    public static void instalar() {
        try {
            MetalLookAndFeel.setCurrentTheme(new TemaMetal());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException e) {
            return; // sem o Metal o programa ainda funciona, só fica com a aparência padrão
        }
        FontUIResource corpo = new FontUIResource(fonteUI(Font.PLAIN, 12.5f));
        FontUIResource negrito = new FontUIResource(fonteUI(Font.BOLD, 12f));
        FontUIResource mono = new FontUIResource(fonteMono(Font.PLAIN, 12.5f));
        for (String chave : new String[] {"Label.font", "CheckBox.font", "RadioButton.font", "ComboBox.font",
            "List.font", "Menu.font", "MenuItem.font", "MenuBar.font", "OptionPane.messageFont", "Spinner.font",
            "ToolTip.font", "TitledBorder.font", "FileChooser.listFont", "Tree.font", "PopupMenu.font",
            "CheckBoxMenuItem.font", "ProgressBar.font", "TableHeader.font"}) {
            UIManager.put(chave, corpo);
        }
        for (String chave : new String[] {"Button.font", "ToggleButton.font", "OptionPane.buttonFont",
            "TabbedPane.font"}) {
            UIManager.put(chave, negrito);
        }
        for (String chave : new String[] {"TextField.font", "FormattedTextField.font", "TextArea.font",
            "TextPane.font", "EditorPane.font", "PasswordField.font", "Table.font"}) {
            UIManager.put(chave, mono);
        }

        ColorUIResource texto = new ColorUIResource(TEXTO);
        ColorUIResource campo = new ColorUIResource(FUNDO_CAMPO);
        ColorUIResource painel = new ColorUIResource(FUNDO_PAINEL);
        ColorUIResource selecao = new ColorUIResource(new Color(0x1F4E7C));
        for (String chave : new String[] {"Panel.background", "OptionPane.background", "ScrollPane.background",
            "Viewport.background", "CheckBox.background", "RadioButton.background", "SplitPane.background",
            "Label.background", "Slider.background", "ToolBar.background", "MenuBar.background",
            "PopupMenu.background", "Menu.background", "MenuItem.background", "CheckBoxMenuItem.background",
            "FileChooser.background", "ProgressBar.background", "Spinner.background", "TabbedPane.background"}) {
            UIManager.put(chave, painel);
        }
        for (String chave : new String[] {"Label.foreground", "CheckBox.foreground", "RadioButton.foreground",
            "OptionPane.messageForeground", "OptionPane.foreground", "Menu.foreground", "MenuItem.foreground",
            "MenuBar.foreground", "PopupMenu.foreground", "CheckBoxMenuItem.foreground", "Button.foreground",
            "ToggleButton.foreground", "TitledBorder.titleColor", "Spinner.foreground", "ComboBox.foreground",
            "List.foreground", "Table.foreground", "TableHeader.foreground", "ProgressBar.foreground"}) {
            UIManager.put(chave, texto);
        }
        for (String chave : new String[] {"TextField.background", "FormattedTextField.background",
            "TextArea.background", "TextPane.background", "EditorPane.background", "List.background",
            "Table.background", "ComboBox.background", "PasswordField.background", "TextField.inactiveBackground",
            "FormattedTextField.inactiveBackground", "TextArea.inactiveBackground", "ComboBox.disabledBackground"}) {
            UIManager.put(chave, campo);
        }
        for (String chave : new String[] {"TextField.foreground", "FormattedTextField.foreground",
            "TextArea.foreground", "TextPane.foreground", "EditorPane.foreground", "PasswordField.foreground",
            "TextField.caretForeground", "TextArea.caretForeground", "FormattedTextField.caretForeground"}) {
            UIManager.put(chave, new ColorUIResource(VERDE));
        }
        for (String chave : new String[] {"TextField.selectionBackground", "FormattedTextField.selectionBackground",
            "TextArea.selectionBackground", "List.selectionBackground", "Table.selectionBackground",
            "ComboBox.selectionBackground", "TextPane.selectionBackground", "EditorPane.selectionBackground"}) {
            UIManager.put(chave, selecao);
        }
        for (String chave : new String[] {"TextField.selectionForeground", "FormattedTextField.selectionForeground",
            "TextArea.selectionForeground", "List.selectionForeground", "Table.selectionForeground",
            "ComboBox.selectionForeground", "TextPane.selectionForeground", "EditorPane.selectionForeground"}) {
            UIManager.put(chave, new ColorUIResource(Color.WHITE));
        }
        UIManager.put("Button.background", new ColorUIResource(FUNDO_ELEVADO));
        UIManager.put("ToggleButton.background", new ColorUIResource(FUNDO_ELEVADO));
        UIManager.put("Button.disabledText", new ColorUIResource(DESABILITADO));
        UIManager.put("Label.disabledForeground", new ColorUIResource(DESABILITADO));
        UIManager.put("Table.gridColor", new ColorUIResource(MOLDURA_ESCURA));
        UIManager.put("Table.focusCellHighlightBorder", new BorderUIResource(BorderFactory.createEmptyBorder()));
        UIManager.put("TableHeader.background", new ColorUIResource(FUNDO_ELEVADO));
        UIManager.put("ToolTip.background", new ColorUIResource(FUNDO_ELEVADO));
        UIManager.put("ToolTip.foreground", texto);
        UIManager.put("ToolTip.border", new BorderUIResource(BorderFactory.createLineBorder(MOLDURA)));
        UIManager.put("ProgressBar.foreground", new ColorUIResource(VERDE));
        UIManager.put("ProgressBar.selectionForeground", new ColorUIResource(Color.WHITE));
        UIManager.put("ProgressBar.selectionBackground", new ColorUIResource(Color.WHITE));
        UIManager.put("ProgressBar.border", new BorderUIResource(BorderFactory.createLineBorder(MOLDURA)));
        UIManager.put("ScrollPane.border", new BorderUIResource(BorderFactory.createLineBorder(MOLDURA)));
        UIManager.put("SplitPane.dividerSize", 5);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.minimumThumbSize", new java.awt.Dimension(24, 24));
        UIManager.put("Button.border", new BorderUIResource.EmptyBorderUIResource(5, 14, 5, 14));
        UIManager.put("ToggleButton.border", new BorderUIResource.EmptyBorderUIResource(5, 14, 5, 14));
        UIManager.put("ComboBox.border", new BorderUIResource(BorderFactory.createLineBorder(MOLDURA)));
        UIManager.put("TextField.border", new BorderUIResource(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(MOLDURA), BorderFactory.createEmptyBorder(2, 5, 2, 5))));
        UIManager.put("FormattedTextField.border", UIManager.get("TextField.border"));
        UIManager.put("TextArea.border", new BorderUIResource(BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        UIManager.put("Spinner.border", new BorderUIResource(BorderFactory.createLineBorder(MOLDURA)));
        UIManager.put("OptionPane.border", new BorderUIResource(BorderFactory.createEmptyBorder(14, 14, 10, 14)));
        UIManager.put("PopupMenu.border", new BorderUIResource(BorderFactory.createLineBorder(MOLDURA)));
        UIManager.put("MenuBar.border", new BorderUIResource(BorderFactory.createMatteBorder(0, 0, 1, 0, MOLDURA_ESCURA)));
        UIManager.put("Menu.selectionBackground", selecao);
        UIManager.put("MenuItem.selectionBackground", selecao);
        UIManager.put("CheckBoxMenuItem.selectionBackground", selecao);
        UIManager.put("Menu.selectionForeground", new ColorUIResource(Color.WHITE));
        UIManager.put("MenuItem.selectionForeground", new ColorUIResource(Color.WHITE));
        UIManager.put("CheckBoxMenuItem.selectionForeground", new ColorUIResource(Color.WHITE));
        UIManager.put("Menu.acceleratorForeground", new ColorUIResource(TEXTO_FRACO));
        UIManager.put("MenuItem.acceleratorForeground", new ColorUIResource(TEXTO_FRACO));
        UIManager.put("Separator.foreground", new ColorUIResource(MOLDURA_ESCURA));
        UIManager.put("Separator.background", painel);

        UIManager.put("ButtonUI", TemaUI.Botao.class.getName());
        UIManager.put("ToggleButtonUI", TemaUI.Botao.class.getName());
        UIManager.put("ComboBoxUI", TemaUI.Combo.class.getName());
        UIManager.put("TabbedPaneUI", TemaUI.Abas.class.getName());
        UIManager.put("ScrollBarUI", TemaUI.Rolagem.class.getName());
        UIManager.put("SliderUI", TemaUI.Deslizante.class.getName());
        UIManager.put("CheckBox.icon", new TemaUI.IconeCaixa());
    }
}
