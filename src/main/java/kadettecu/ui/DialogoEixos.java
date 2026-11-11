package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import kadettecu.modelo.EixosMapa;
import kadettecu.modelo.MapaECU;

/**
 * Janela para editar os dois eixos compartilhados pelas três tabelas: a rotação de cada coluna
 * e a carga (MAP em kPa) de cada linha. Os valores precisam ser crescentes.
 */
public final class DialogoEixos extends JDialog {

    private static final long serialVersionUID = 1L;
    private static final int N = MapaECU.TAMANHO;

    private final JSpinner[] rpm = new JSpinner[N];
    private final JSpinner[] carga = new JSpinner[N];
    private transient EixosMapa resultado;

    private DialogoEixos(Window dono, EixosMapa atual) {
        super(dono, "Eixos das tabelas", ModalityType.APPLICATION_MODAL);
        JPanel raiz = new JPanel(new BorderLayout(10, 10));
        raiz.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel grades = new JPanel(new GridLayout(1, 2, 12, 0));
        grades.setOpaque(false);
        grades.add(criarGrade("ROTAÇÃO DAS COLUNAS (rpm)", rpm, atual, true));
        grades.add(criarGrade("CARGA DAS LINHAS (MAP, kPa)", carga, atual, false));
        raiz.add(grades, BorderLayout.CENTER);

        JLabel dica = new JLabel("Os valores precisam ser crescentes. O eixo vale para as três tabelas.");
        dica.setForeground(Tema.TEXTO_FRACO);
        raiz.add(dica, BorderLayout.NORTH);

        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        botoes.setOpaque(false);
        JButton padrao = new JButton("RESTAURAR PADRÃO");
        JButton cancelar = new JButton("CANCELAR");
        JButton aplicar = new JButton("APLICAR");
        padrao.addActionListener(e -> preencher(EixosMapa.padrao()));
        cancelar.addActionListener(e -> dispose());
        aplicar.addActionListener(e -> aplicar());
        botoes.add(padrao);
        botoes.add(cancelar);
        botoes.add(aplicar);
        raiz.add(botoes, BorderLayout.SOUTH);

        setContentPane(raiz);
        getRootPane().setDefaultButton(aplicar);
        pack();
        setResizable(false);
    }

    private static JPanel criarGrade(String titulo, JSpinner[] campos, EixosMapa atual, boolean ehRpm) {
        JPanel grade = new JPanel(new GridLayout(4, 4, 6, 6));
        grade.setBorder(Tema.moldura(titulo));
        for (int i = 0; i < N; i++) {
            int valor = ehRpm ? atual.getRpm(i) : atual.getCarga(i);
            SpinnerNumberModel modelo = ehRpm ? new SpinnerNumberModel(valor, 0, 20000, 50)
                    : new SpinnerNumberModel(valor, 0, 400, 1);
            campos[i] = new JSpinner(modelo);
            ((JSpinner.DefaultEditor) campos[i].getEditor()).getTextField().setColumns(5);
            grade.add(campos[i]);
        }
        return grade;
    }

    private void preencher(EixosMapa eixos) {
        for (int i = 0; i < N; i++) {
            rpm[i].setValue(eixos.getRpm(i));
            carga[i].setValue(eixos.getCarga(i));
        }
    }

    private void aplicar() {
        int[] r = new int[N];
        int[] c = new int[N];
        for (int i = 0; i < N; i++) {
            r[i] = ((Number) rpm[i].getValue()).intValue();
            c[i] = ((Number) carga[i].getValue()).intValue();
        }
        try {
            resultado = EixosMapa.deValores(r, c);
            dispose();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Eixo inválido", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Abre a janela e devolve os novos eixos, ou null se o usuário cancelou. */
    public static EixosMapa mostrar(Component pai, EixosMapa atual) {
        Window dono = SwingUtilities.getWindowAncestor(pai);
        DialogoEixos dialogo = new DialogoEixos(dono, atual);
        dialogo.setLocationRelativeTo(pai);
        dialogo.setVisible(true);
        return dialogo.resultado;
    }
}
