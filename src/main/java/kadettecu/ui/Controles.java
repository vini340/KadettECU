package kadettecu.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.ParseException;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import kadettecu.app.SessaoECU;

/**
 * Pequenas fábricas de componentes e caixas de diálogo que várias telas repetiriam. Ficam num
 * lugar só para que botões, rótulos e mensagens tenham sempre a mesma cara e o mesmo texto.
 */
public final class Controles {

    private Controles() {
    }

    public static JButton botao(String texto, String dica, Runnable acao) {
        JButton botao = new JButton(texto);
        botao.setFont(Tema.fonteTitulo(11f));
        botao.setToolTipText(dica);
        botao.addActionListener(e -> acao.run());
        return botao;
    }

    /**
     * Botão que fica apertado ou solto. Usa o ouvinte de ação (e não o de itens) de propósito: ele só
     * dispara quando o USUÁRIO clica, e não quando o programa troca o estado do botão por código.
     */
    public static JToggleButton alternar(String texto, String dica, boolean marcado, Consumer<Boolean> aoClicar) {
        return alternar(texto, dica, marcado, Tema.VERDE, aoClicar);
    }

    /** Igual ao anterior, mas escolhendo a cor que o botão ganha quando está ligado. */
    public static JToggleButton alternar(String texto, String dica, boolean marcado, Color corLigado,
                                         Consumer<Boolean> aoClicar) {
        JToggleButton botao = new JToggleButton(texto, marcado);
        botao.setFont(Tema.fonteTitulo(11f));
        botao.setToolTipText(dica);
        botao.putClientProperty(TemaUI.COR_LIGADO, corLigado);
        botao.addActionListener(e -> aoClicar.accept(botao.isSelected()));
        return botao;
    }

    /** Rótulo pequeno e apagado, para nomear campos e grupos de botões. */
    public static JLabel rotulo(String texto) {
        JLabel rotulo = new JLabel(texto);
        rotulo.setFont(Tema.fonteTitulo(10.5f));
        rotulo.setForeground(Tema.TEXTO_FRACO);
        return rotulo;
    }

    /** Campo numérico com setinhas; {@code formato} é um padrão de DecimalFormat, por exemplo "0.##". */
    public static JSpinner numero(SpinnerNumberModel modelo, String formato, int colunas) {
        JSpinner spinner = new JSpinner(modelo);
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, formato);
        editor.getTextField().setColumns(colunas);
        editor.getTextField().setHorizontalAlignment(JTextField.RIGHT);
        spinner.setEditor(editor);
        return spinner;
    }

    /**
     * Lê o número do campo já considerando o que o usuário acabou de digitar e ainda não confirmou
     * (as setinhas confirmam sozinhas, mas clicar num botão sem sair do campo não).
     */
    public static double valor(JSpinner campo) {
        try {
            campo.commitEdit();
        } catch (ParseException e) {
            // texto que não é número: vale o último valor válido, que o campo mostra de volta
        }
        return ((Number) campo.getValue()).doubleValue();
    }

    /** Espaço em branco entre grupos de botões. */
    public static Component espaco(int largura) {
        JLabel vazio = new JLabel();
        vazio.setPreferredSize(new Dimension(largura, 1));
        return vazio;
    }

    /** Uma linha de componentes lado a lado, sem fundo, alinhada à esquerda. */
    public static JPanel linha(Component... componentes) {
        JPanel linha = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        linha.setOpaque(false);
        for (Component c : componentes) {
            linha.add(c);
        }
        return linha;
    }

    /** Mesma coisa, mas com o espaçamento escolhido. */
    public static JPanel linha(int espaco, Component... componentes) {
        JPanel linha = new JPanel(new FlowLayout(FlowLayout.LEFT, espaco, 0));
        linha.setOpaque(false);
        for (Component c : componentes) {
            linha.add(c);
        }
        return linha;
    }

    /** Impede que o botão roube o foco do teclado (útil nas barras de ferramentas ao lado de uma tabela). */
    public static <T extends JComponent> T semFoco(T componente) {
        componente.setFocusable(false);
        return componente;
    }

    // ---------------------------------------------------------------- diálogos

    /** Mostra o erro em português, no formato de {@link SessaoECU#descreverErro}. */
    public static void mostrarErro(Component pai, String titulo, Throwable erro) {
        mostrarAviso(pai, titulo, SessaoECU.descreverErro(erro), JOptionPane.ERROR_MESSAGE);
    }

    public static void mostrarAviso(Component pai, String titulo, String mensagem, int tipo) {
        JOptionPane.showMessageDialog(pai, html(mensagem), titulo, tipo);
    }

    /**
     * Pergunta com dois botões com texto próprio. O segundo é o padrão (a opção segura), então
     * apertar Enter sem querer não faz a ação perigosa.
     */
    public static boolean confirmar(Component pai, String titulo, String mensagem, String sim, String nao) {
        int escolha = JOptionPane.showOptionDialog(pai, html(mensagem), titulo, JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE, null, new Object[] {sim, nao}, nao);
        return escolha == 0;
    }

    /**
     * Pergunta com quantas opções forem necessárias; devolve o índice da escolhida (ou -1 se a janela
     * foi fechada). {@code padrao} é o índice do botão que Enter aciona: escolha sempre a opção segura.
     */
    public static int escolher(Component pai, String titulo, String mensagem, int padrao, String... opcoes) {
        return JOptionPane.showOptionDialog(pai, html(mensagem), titulo, JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, opcoes, opcoes[padrao]);
    }

    /** Quebra o texto em linhas de largura razoável (o JOptionPane não quebra sozinho). */
    private static String html(String texto) {
        String limpo = texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return "<html><body style='width:380px'>" + limpo + "</body></html>";
    }
}
