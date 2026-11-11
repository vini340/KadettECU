package kadettecu.ui;

import javax.swing.SwingUtilities;
import kadettecu.app.SessaoECU;
import kadettecu.modelo.EstadoConexao;

/**
 * Embrulha um ouvinte da sessão para que ele sempre execute na thread do Swing, não importa em
 * que thread a sessão tenha disparado o aviso. É o padrão "Decorator": o ouvinte de verdade
 * (um painel, por exemplo) não precisa se preocupar com threads.
 */
public final class OuvinteNaTela implements SessaoECU.Ouvinte {

    private final SessaoECU.Ouvinte destino;

    public OuvinteNaTela(SessaoECU.Ouvinte destino) {
        this.destino = destino;
    }

    private static void naTela(Runnable acao) {
        if (SwingUtilities.isEventDispatchThread()) {
            acao.run();
        } else {
            SwingUtilities.invokeLater(acao);
        }
    }

    @Override
    public void aoMudarEstado(EstadoConexao estado) {
        naTela(() -> destino.aoMudarEstado(estado));
    }

    @Override
    public void aoRegistrar(String mensagem) {
        naTela(() -> destino.aoRegistrar(mensagem));
    }

    @Override
    public void aoSubstituirCalibracao() {
        naTela(destino::aoSubstituirCalibracao);
    }

    @Override
    public void aoMudarSituacaoCalibracao(boolean alterada) {
        naTela(() -> destino.aoMudarSituacaoCalibracao(alterada));
    }

    @Override
    public void aoProgredir(String operacao, int feitos, int total) {
        naTela(() -> destino.aoProgredir(operacao, feitos, total));
    }
}
