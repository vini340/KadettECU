package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import kadettecu.app.SessaoECU;

/**
 * A aba REGISTRO: tudo o que a comunicação conta (conexão, falhas, telemetria que sumiu e voltou,
 * respostas que não vieram...) com a hora de cada mensagem. É o primeiro lugar para olhar quando
 * algo não funciona. Guarda só as últimas 2000 linhas para não crescer sem fim.
 */
public final class PainelRegistro extends JPanel implements SessaoECU.Ouvinte {

    private static final long serialVersionUID = 1L;
    private static final int LIMITE_LINHAS = 2000;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final JTextArea texto = new JTextArea();
    private final JCheckBox rolagemAutomatica = new JCheckBox("ROLAR AUTOMATICAMENTE", true);

    public PainelRegistro(SessaoECU sessao) {
        super(new BorderLayout(0, 8));
        setBackground(Tema.FUNDO_PAINEL);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        texto.setEditable(false);
        texto.setFont(Tema.fonteMono(Font.PLAIN, 12.5f));
        JScrollPane rolagem = new JScrollPane(texto);
        rolagem.setBorder(BorderFactory.createLineBorder(Tema.MOLDURA_ESCURA));
        JPanel centro = new JPanel(new BorderLayout());
        centro.setOpaque(false);
        centro.setBorder(Tema.moldura("REGISTRO DE EVENTOS"));
        centro.add(rolagem, BorderLayout.CENTER);

        rolagemAutomatica.setOpaque(false);
        rolagemAutomatica.setFont(Tema.fonteTitulo(10.5f));
        JPanel barra = Controles.linha(8,
                Controles.botao("LIMPAR", "Apaga o registro da tela", () -> texto.setText("")),
                Controles.botao("COPIAR", "Copia todo o registro para a área de transferência",
                        () -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(texto.getText()), null)),
                Controles.botao("SALVAR...", "Grava o registro num arquivo de texto", this::salvar),
                rolagemAutomatica);
        add(barra, BorderLayout.NORTH);
        add(centro, BorderLayout.CENTER);

        escrever("Registro iniciado. Escolha uma porta e clique em CONECTAR.");
        sessao.adicionarOuvinte(new OuvinteNaTela(this));
    }

    @Override
    public void aoRegistrar(String mensagem) {
        escrever(mensagem);
    }

    private void escrever(String mensagem) {
        texto.append(HORA.format(LocalTime.now()) + "  " + mensagem + "\n");
        limitar();
        if (rolagemAutomatica.isSelected()) {
            texto.setCaretPosition(texto.getDocument().getLength());
        }
    }

    /** Remove as linhas mais antigas quando passa do limite. */
    private void limitar() {
        int excesso = texto.getLineCount() - 1 - LIMITE_LINHAS;
        if (excesso <= 0) {
            return;
        }
        try {
            texto.replaceRange("", 0, texto.getLineEndOffset(excesso - 1));
        } catch (BadLocationException e) {
            texto.setText("");
        }
    }

    private void salvar() {
        JFileChooser seletor = new JFileChooser();
        seletor.setSelectedFile(new java.io.File("registro-kadettecu.txt"));
        if (seletor.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destino = seletor.getSelectedFile().toPath();
        try {
            Files.write(destino, texto.getText().getBytes(StandardCharsets.UTF_8));
            escrever("Registro salvo em " + destino.toAbsolutePath());
        } catch (IOException e) {
            Controles.mostrarErro(this, "Salvar registro", e);
        }
    }
}
