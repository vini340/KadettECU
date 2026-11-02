package kadettecu.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import kadettecu.analise.ArquivoECU;
import kadettecu.analise.ControladorECU;
import kadettecu.analise.RelatorioHex;
import kadettecu.analise.ResultadoAnalise;
import kadettecu.app.SessaoECU;

/**
 * A aba BINÁRIO / HEX: o analisador de arquivos de ECU que já existia no projeto (procura um byte
 * dentro do arquivo e mostra um HEX viewer), agora dentro da janela nova. Ele usa as MESMAS classes
 * de antes ({@link ControladorECU}, {@link ArquivoECU}, {@link RelatorioHex}); esta classe só cuida da tela.
 *
 * <p>A leitura e a montagem do relatório rodam num {@link SwingWorker} (thread de fundo), porque um
 * arquivo grande deixaria a janela travada se isso fosse feito na thread da tela. O resultado volta
 * para a tela no método {@code done()}, que o Swing já executa na thread certa.
 */
public final class PainelBinario extends JPanel {

    private static final long serialVersionUID = 1L;
    /** Acima disso o relatório de texto ficaria enorme e pesado para o JTextArea. */
    private static final long TAMANHO_MAXIMO = 2L * 1024 * 1024;
    private static final String TEXTO_INTRODUCAO = String.join("\n",
            "Este visualizador mostra qualquer arquivo em hexadecimal e procura um byte dentro dele",
            "(é o analisador de arquivos original do projeto, agora dentro do painel).",
            "",
            "  ABRIR ARQUIVO...    escolhe o arquivo: um .bin lido de uma ECU, um .kecu, um .txt...",
            "  CALIBRAÇÃO ATUAL    mostra a calibração que está na tela, como ela ficaria num arquivo .kecu",
            "  BYTE (HEX)          o valor a procurar, de 00 a FF",
            "  ANALISAR            conta quantas vezes o byte aparece, diz em que endereços e monta o HEX");

    private final transient SessaoECU sessao;
    private final JTextField campoArquivo = new JTextField();
    private final JTextField campoHex = new JTextField("FF", 3);
    private final JButton botaoAnalisar;
    private final JTextArea saida = new JTextArea();
    private final JLabel resumo = new JLabel("Escolha um arquivo (por exemplo, um .bin lido de uma ECU ou um .kecu) e o byte a procurar.");
    private transient Path arquivoAtual;

    /** O que o trabalho de fundo devolve para a tela. */
    private static final class Resposta {
        private final String texto;
        private final int ocorrencias;
        private final int bytes;
        private final int valor;

        Resposta(String texto, int ocorrencias, int bytes, int valor) {
            this.texto = texto;
            this.ocorrencias = ocorrencias;
            this.bytes = bytes;
            this.valor = valor;
        }
    }

    public PainelBinario(SessaoECU sessao) {
        super(new BorderLayout(0, 8));
        this.sessao = sessao;
        setBackground(Tema.FUNDO_PAINEL);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        campoArquivo.setEditable(false);
        campoHex.setHorizontalAlignment(JTextField.CENTER);
        campoHex.setFont(Tema.fonteMono(Font.BOLD, 14f));
        campoHex.addActionListener(e -> analisar());
        botaoAnalisar = Controles.botao("ANALISAR", "Procura o byte no arquivo e monta o HEX viewer", this::analisar);

        saida.setEditable(false);
        saida.setFont(Tema.fonteMono(Font.BOLD, 13f));
        saida.setLineWrap(false);
        saida.setMargin(new java.awt.Insets(8, 10, 8, 10));
        saida.setText(TEXTO_INTRODUCAO);
        JScrollPane rolagem = new JScrollPane(saida);
        rolagem.setBorder(BorderFactory.createLineBorder(Tema.MOLDURA_ESCURA));
        JPanel centro = new JPanel(new BorderLayout());
        centro.setOpaque(false);
        centro.setBorder(Tema.moldura("BINÁRIO / HEX"));
        centro.add(rolagem, BorderLayout.CENTER);

        resumo.setFont(Tema.fonteUI(Font.PLAIN, 12f));
        resumo.setForeground(Tema.TEXTO_FRACO);

        add(montarTopo(), BorderLayout.NORTH);
        add(centro, BorderLayout.CENTER);
        add(resumo, BorderLayout.SOUTH);
    }

    private JPanel montarTopo() {
        JPanel topo = new JPanel(new BorderLayout(8, 0));
        topo.setOpaque(false);
        JPanel esquerda = Controles.linha(
                Controles.botao("ABRIR ARQUIVO...", "Escolhe o arquivo a analisar", this::escolherArquivo),
                Controles.botao("CALIBRAÇÃO ATUAL", "Salva a calibração que está na tela num arquivo temporário e a abre aqui",
                        this::usarCalibracaoAtual));
        topo.add(esquerda, BorderLayout.WEST);
        topo.add(campoArquivo, BorderLayout.CENTER);
        JPanel direita = Controles.linha(Controles.rotulo("BYTE (HEX)"), campoHex, botaoAnalisar);
        topo.add(direita, BorderLayout.EAST);
        return topo;
    }

    // ---------------------------------------------------------------- escolha do arquivo

    private void escolherArquivo() {
        JFileChooser seletor = new JFileChooser();
        if (arquivoAtual != null && arquivoAtual.getParent() != null) {
            seletor.setCurrentDirectory(arquivoAtual.getParent().toFile());
        }
        if (seletor.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File escolhido = seletor.getSelectedFile();
        definirArquivo(escolhido.toPath());
        analisar();
    }

    private void usarCalibracaoAtual() {
        final Path temporario;
        try {
            temporario = Files.createTempFile("kadettecu-calibracao-", ".kecu");
        } catch (IOException e) {
            mostrarResumo("Não foi possível criar o arquivo temporário: " + e.getMessage(), Tema.VERMELHO);
            return;
        }
        temporario.toFile().deleteOnExit();
        Tarefa.aoTerminarOk(sessao.salvarArquivo(temporario), () -> {
            definirArquivo(temporario);
            analisar();
        }, erro -> mostrarResumo("Não foi possível salvar a calibração: " + SessaoECU.descreverErro(erro), Tema.VERMELHO));
    }

    private void definirArquivo(Path arquivo) {
        arquivoAtual = arquivo;
        campoArquivo.setText(arquivo.toAbsolutePath().toString());
        campoArquivo.setCaretPosition(0);
    }

    // ---------------------------------------------------------------- análise

    private void analisar() {
        if (arquivoAtual == null) {
            mostrarResumo("Escolha um arquivo primeiro.", Tema.AMBAR);
            return;
        }
        final int valor;
        try {
            valor = Integer.parseInt(campoHex.getText().trim(), 16);
            if (valor < 0 || valor > 255) {
                throw new NumberFormatException("fora de 00..FF");
            }
        } catch (NumberFormatException e) {
            mostrarResumo("HEX inválido! Digite um byte de 00 a FF (por exemplo, FF ou 3A).", Tema.AMBAR);
            return;
        }
        final ArquivoECU arquivo = new ArquivoECU(arquivoAtual.toString());
        if (!arquivo.existe()) {
            mostrarResumo("Arquivo não encontrado: " + arquivo.getCaminhoAbsoluto(), Tema.AMBAR);
            return;
        }
        if (arquivo.getTamanho() > TAMANHO_MAXIMO) {
            mostrarResumo("Arquivo grande demais para o visualizador (" + arquivo.getTamanho() / 1024 + " KB; o limite é "
                    + TAMANHO_MAXIMO / 1024 + " KB).", Tema.AMBAR);
            return;
        }

        botaoAnalisar.setEnabled(false);
        saida.setText("");
        mostrarResumo("Analisando...", Tema.CIANO);
        new SwingWorker<Resposta, Void>() {
            @Override
            protected Resposta doInBackground() {
                // Reaproveita o controlador de sempre; o "exibidor" só guarda o resultado para montarmos o texto aqui.
                AtomicReference<ResultadoAnalise> recebido = new AtomicReference<>();
                ControladorECU controlador = new ControladorECU();
                controlador.adicionarExibidor(recebido::set);
                controlador.analisar(arquivo.getCaminho(), valor);
                ResultadoAnalise resultado = recebido.get();
                if (resultado == null) {
                    return null;
                }
                return new Resposta(RelatorioHex.montar(resultado), resultado.getEnderecos().size(),
                        resultado.getQuantidadeBytes(), resultado.getValorProcurado());
            }

            @Override
            protected void done() {
                botaoAnalisar.setEnabled(true);
                try {
                    Resposta resposta = get();
                    if (resposta == null) {
                        mostrarResumo("Não foi possível ler o arquivo (veja o console para o motivo).", Tema.VERMELHO);
                        return;
                    }
                    saida.setText(resposta.texto);
                    saida.setCaretPosition(0);
                    mostrarResumo(String.format("%d ocorrência(s) de 0x%02X em %d byte(s).", resposta.ocorrencias,
                            resposta.valor, resposta.bytes), resposta.ocorrencias > 0 ? Tema.VERDE : Tema.AMBAR);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    mostrarResumo("Erro ao analisar: " + SessaoECU.descreverErro(e), Tema.VERMELHO);
                }
            }
        }.execute();
    }

    private void mostrarResumo(String texto, Color cor) {
        resumo.setForeground(cor);
        resumo.setText(texto);
    }
}
