package kadettecu.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.basic.ComboPopup;

/**
 * Os desenhos próprios dos componentes do Swing (o que o Swing chama de "UI delegates").
 *
 * <p>No Swing, um JButton não se desenha sozinho: ele pede a um objeto "UI" que faça isso. Trocar
 * esse objeto muda a aparência de TODOS os botões do programa, inclusive os de caixas de diálogo,
 * sem mexer em nenhuma tela. {@link Tema#instalar()} registra as classes abaixo no UIManager.
 */
public final class TemaUI {

    /** Propriedade (um {@link Color}) que escolhe a cor de um botão de alternar quando ele está ligado; o padrão é o verde neon. */
    public static final String COR_LIGADO = "kecu.corLigado";

    private TemaUI() {
    }

    private static Color corLigado(AbstractButton botao) {
        Object cor = botao.getClientProperty(COR_LIGADO);
        return cor instanceof Color ? (Color) cor : Tema.VERDE;
    }

    // ------------------------------------------------------------------ botões (JButton e JToggleButton)

    /** Botão plano com contorno de aço; ligado (JToggleButton selecionado) fica verde neon (ou na cor de {@link #COR_LIGADO}). */
    public static final class Botao extends BasicButtonUI {

        public static ComponentUI createUI(JComponent c) {
            return new Botao();
        }

        @Override
        protected void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            b.setOpaque(false);
            b.setRolloverEnabled(true);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            Graphics2D g2 = (Graphics2D) g.create();
            Tema.suavizar(g2);
            int w = c.getWidth();
            int h = c.getHeight();
            boolean ligado = b.isSelected();
            Color destaque = corLigado(b);
            Color fundo;
            Color borda;
            if (!b.isEnabled()) {
                fundo = new Color(0x08131F);
                borda = new Color(0x1F3248);
            } else if (m.isPressed() && m.isArmed()) {
                fundo = new Color(0x1F4E7C);
                borda = Tema.MOLDURA_CLARA;
            } else if (ligado) {
                fundo = Tema.escurecer(destaque, 0.78);
                borda = destaque;
            } else if (m.isRollover() || b.hasFocus()) {
                fundo = new Color(0x15355A);
                borda = Tema.MOLDURA_CLARA;
            } else {
                fundo = new Color(0x0D1E34);
                borda = Tema.MOLDURA;
            }
            g2.setPaint(new GradientPaint(0, 0, Tema.clarear(fundo, 0.10), 0, h, fundo));
            g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 7, 7));
            if (ligado && b.isEnabled()) {
                g2.setColor(Tema.comAlfa(destaque, 40));
                g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, 6, 6));
            }
            g2.setColor(borda);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1.5f, h - 1.5f, 7, 7));
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        protected void paintText(Graphics g, JComponent c, Rectangle areaTexto, String texto) {
            AbstractButton b = (AbstractButton) c;
            Graphics2D g2 = (Graphics2D) g;
            Tema.suavizar(g2);
            FontMetrics fm = g2.getFontMetrics();
            Color cor;
            if (!b.isEnabled()) {
                cor = Tema.DESABILITADO;
            } else if (b.isSelected()) {
                cor = corLigado(b);
            } else {
                cor = b.getForeground();
            }
            g2.setColor(cor);
            BasicGraphicsUtils.drawStringUnderlineCharAt(g2, texto, b.getDisplayedMnemonicIndex(),
                    areaTexto.x + getTextShiftOffset(), areaTexto.y + fm.getAscent() + getTextShiftOffset());
        }

        @Override
        protected void paintFocus(Graphics g, AbstractButton b, Rectangle areaVista, Rectangle areaTexto,
                                  Rectangle areaIcone) {
            // o foco já aparece no contorno claro do botão
        }
    }

    // ------------------------------------------------------------------ lista suspensa (JComboBox)

    public static final class Combo extends BasicComboBoxUI {

        public static ComponentUI createUI(JComponent c) {
            return new Combo();
        }

        @Override
        protected JButton createArrowButton() {
            return new SetaCombo();
        }

        @Override
        public void paintCurrentValueBackground(Graphics g, Rectangle area, boolean temFoco) {
            g.setColor(temFoco && !comboBox.isPopupVisible() ? new Color(0x0F2B49) : Tema.FUNDO_CAMPO);
            g.fillRect(area.x, area.y, area.width, area.height);
        }

        @Override
        protected ComboPopup createPopup() {
            BasicComboPopup popup = new BasicComboPopup(comboBox);
            popup.setBorder(BorderFactory.createLineBorder(Tema.MOLDURA_CLARA));
            return popup;
        }
    }

    private static final class SetaCombo extends JButton {

        private static final long serialVersionUID = 1L;

        SetaCombo() {
            setBorder(BorderFactory.createEmptyBorder());
            setFocusable(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setPreferredSize(new Dimension(22, 22));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            int w = getWidth();
            int h = getHeight();
            g.setColor(getModel().isRollover() || getModel().isPressed() ? new Color(0x1B466F) : Tema.FUNDO_ELEVADO);
            g.fillRect(0, 0, w, h);
            g.setColor(Tema.MOLDURA_ESCURA);
            g.drawLine(0, 0, 0, h);
            Path2D seta = new Path2D.Double();
            double cx = w / 2.0;
            double cy = h / 2.0;
            seta.moveTo(cx - 4.5, cy - 2);
            seta.lineTo(cx + 4.5, cy - 2);
            seta.lineTo(cx, cy + 3.2);
            seta.closePath();
            g.setColor(isEnabled() ? Tema.CIANO : Tema.DESABILITADO);
            g.fill(seta);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ abas (JTabbedPane)

    /** Abas em forma de "etiqueta" com o canto chanfrado; a aba escolhida fica azul-clara. */
    public static final class Abas extends BasicTabbedPaneUI {

        public static ComponentUI createUI(JComponent c) {
            return new Abas();
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(5, 18, 5, 18);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
            tabAreaInsets = new Insets(6, 4, 0, 4);
            contentBorderInsets = new Insets(1, 1, 1, 1);
            tabRunOverlay = 0;
        }

        private static Polygon forma(int x, int y, int w, int h) {
            Polygon p = new Polygon();
            p.addPoint(x, y + h);
            p.addPoint(x, y + 5);
            p.addPoint(x + 5, y);
            p.addPoint(x + w - 6, y);
            p.addPoint(x + w - 1, y + 5);
            p.addPoint(x + w - 1, y + h);
            return p;
        }

        @Override
        protected void paintTabBackground(Graphics g, int posicao, int indice, int x, int y, int w, int h,
                                          boolean selecionada) {
            Graphics2D g2 = (Graphics2D) g.create();
            Tema.suavizar(g2);
            Color base;
            if (selecionada) {
                base = Tema.CELULA;
            } else if (indice == getRolloverTab()) {
                base = new Color(0x1B466F);
            } else {
                base = Tema.FUNDO_ELEVADO;
            }
            g2.setPaint(new GradientPaint(x, y, Tema.clarear(base, selecionada ? 0.35 : 0.08), x, y + h, base));
            g2.fill(forma(x, y, w, h));
            g2.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics g, int posicao, int indice, int x, int y, int w, int h,
                                      boolean selecionada) {
            Graphics2D g2 = (Graphics2D) g.create();
            Tema.suavizar(g2);
            g2.setColor(selecionada ? Tema.MOLDURA_CLARA : Tema.MOLDURA);
            Polygon p = forma(x, y, w, h);
            g2.drawPolyline(p.xpoints, p.ypoints, p.npoints);
            g2.dispose();
        }

        @Override
        protected void paintText(Graphics g, int posicao, java.awt.Font fonte, FontMetrics fm, int indice,
                                 String titulo, Rectangle areaTexto, boolean selecionada) {
            Graphics2D g2 = (Graphics2D) g;
            Tema.suavizar(g2);
            g2.setFont(fonte);
            g2.setColor(!tabPane.isEnabledAt(indice) ? Tema.DESABILITADO : (selecionada ? Tema.FUNDO : Tema.TEXTO));
            BasicGraphicsUtils.drawStringUnderlineCharAt(g2, titulo, tabPane.getDisplayedMnemonicIndexAt(indice),
                    areaTexto.x, areaTexto.y + fm.getAscent());
        }

        @Override
        protected void paintFocusIndicator(Graphics g, int posicao, Rectangle[] retangulos, int indice,
                                           Rectangle areaIcone, Rectangle areaTexto, boolean selecionada) {
            // sem pontilhado de foco: a aba escolhida já se destaca
        }

        @Override
        protected int getTabLabelShiftX(int posicao, int indice, boolean selecionada) {
            return 0;
        }

        @Override
        protected int getTabLabelShiftY(int posicao, int indice, boolean selecionada) {
            return 0;
        }

        @Override
        protected void paintContentBorderTopEdge(Graphics g, int posicao, int selecionada, int x, int y, int w, int h) {
            g.setColor(Tema.MOLDURA_CLARA);
            Rectangle aba = selecionada < 0 ? null : rects[selecionada];
            if (aba == null || posicao != TOP) {
                g.drawLine(x, y, x + w - 1, y);
                return;
            }
            g.drawLine(x, y, aba.x, y);
            g.drawLine(aba.x + aba.width - 1, y, x + w - 1, y);
        }

        @Override
        protected void paintContentBorderLeftEdge(Graphics g, int posicao, int selecionada, int x, int y, int w, int h) {
            g.setColor(Tema.MOLDURA);
            g.drawLine(x, y, x, y + h - 1);
        }

        @Override
        protected void paintContentBorderBottomEdge(Graphics g, int posicao, int selecionada, int x, int y, int w,
                                                    int h) {
            g.setColor(Tema.MOLDURA);
            g.drawLine(x, y + h - 1, x + w - 1, y + h - 1);
        }

        @Override
        protected void paintContentBorderRightEdge(Graphics g, int posicao, int selecionada, int x, int y, int w,
                                                   int h) {
            g.setColor(Tema.MOLDURA);
            g.drawLine(x + w - 1, y, x + w - 1, y + h - 1);
        }
    }

    // ------------------------------------------------------------------ barra de rolagem

    public static final class Rolagem extends BasicScrollBarUI {

        public static ComponentUI createUI(JComponent c) {
            return new Rolagem();
        }

        private static JButton botaoInvisivel() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            b.setFocusable(false);
            return b;
        }

        @Override
        protected JButton createDecreaseButton(int orientacao) {
            return botaoInvisivel();
        }

        @Override
        protected JButton createIncreaseButton(int orientacao) {
            return botaoInvisivel();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(Tema.FUNDO_CAMPO);
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(Tema.MOLDURA_ESCURA);
            if (scrollbar.getOrientation() == SwingConstants.VERTICAL) {
                g.drawLine(r.x, r.y, r.x, r.y + r.height);
            } else {
                g.drawLine(r.x, r.y, r.x + r.width, r.y);
            }
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            Tema.suavizar(g2);
            Color cor = isDragging ? Tema.CIANO : (isThumbRollover() ? Tema.MOLDURA_CLARA : Tema.MOLDURA);
            g2.setColor(cor);
            g2.fill(new RoundRectangle2D.Float(r.x + 2.5f, r.y + 2.5f, r.width - 5f, r.height - 5f, 6, 6));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ deslizante (JSlider)

    public static final class Deslizante extends BasicSliderUI {

        public Deslizante(JSlider slider) {
            super(slider);
        }

        public static ComponentUI createUI(JComponent c) {
            return new Deslizante((JSlider) c);
        }

        @Override
        protected Dimension getThumbSize() {
            return slider.getOrientation() == SwingConstants.HORIZONTAL ? new Dimension(10, 18) : new Dimension(18, 10);
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Tema.suavizar(g2);
            boolean ativo = slider.isEnabled();
            if (slider.getOrientation() == SwingConstants.HORIZONTAL) {
                int y = trackRect.y + trackRect.height / 2;
                int x0 = trackRect.x;
                int x1 = trackRect.x + trackRect.width;
                int xp = thumbRect.x + thumbRect.width / 2;
                g2.setColor(Tema.MOLDURA_ESCURA);
                g2.fillRoundRect(x0, y - 2, x1 - x0, 4, 4, 4);
                g2.setColor(ativo ? Tema.VERDE : Tema.DESABILITADO);
                g2.fillRoundRect(x0, y - 2, Math.max(0, xp - x0), 4, 4, 4);
            } else {
                int x = trackRect.x + trackRect.width / 2;
                int y0 = trackRect.y;
                int y1 = trackRect.y + trackRect.height;
                int yp = thumbRect.y + thumbRect.height / 2;
                g2.setColor(Tema.MOLDURA_ESCURA);
                g2.fillRoundRect(x - 2, y0, 4, y1 - y0, 4, 4);
                g2.setColor(ativo ? Tema.VERDE : Tema.DESABILITADO);
                g2.fillRoundRect(x - 2, yp, 4, Math.max(0, y1 - yp), 4, 4);
            }
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            Tema.suavizar(g2);
            g2.setPaint(new GradientPaint(0, thumbRect.y, Tema.CELULA_CLARA, 0, thumbRect.y + thumbRect.height,
                    Tema.CELULA_ESCURA));
            g2.fillRoundRect(thumbRect.x, thumbRect.y, thumbRect.width - 1, thumbRect.height - 1, 4, 4);
            g2.setColor(Tema.FUNDO);
            g2.drawRoundRect(thumbRect.x, thumbRect.y, thumbRect.width - 1, thumbRect.height - 1, 4, 4);
            g2.dispose();
        }

        @Override
        public void paintFocus(Graphics g) {
            // o cursor já muda de cor ao receber o foco do teclado
        }
    }

    // ------------------------------------------------------------------ caixa de seleção (JCheckBox)

    /** Quadradinho com um "visto" verde neon quando marcado. */
    public static final class IconeCaixa implements Icon, UIResource {

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            Graphics2D g = (Graphics2D) g0.create();
            Tema.suavizar(g);
            boolean ativo = b.isEnabled();
            g.setColor(ativo ? Tema.FUNDO_CAMPO : new Color(0x08131F));
            g.fillRoundRect(x + 1, y + 1, 14, 14, 4, 4);
            Color borda = !ativo ? Tema.DESABILITADO
                    : (m.isSelected() ? Tema.VERDE : (m.isRollover() ? Tema.MOLDURA_CLARA : Tema.MOLDURA));
            g.setColor(borda);
            g.drawRoundRect(x + 1, y + 1, 13, 13, 4, 4);
            if (m.isSelected()) {
                Path2D visto = new Path2D.Double();
                visto.moveTo(x + 4, y + 8.5);
                visto.lineTo(x + 7, y + 11.5);
                visto.lineTo(x + 12, y + 4.5);
                g.setColor(ativo ? Tema.VERDE : Tema.DESABILITADO);
                g.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                g.draw(visto);
            }
            g.dispose();
        }
    }
}
