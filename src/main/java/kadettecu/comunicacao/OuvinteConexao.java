package kadettecu.comunicacao;

import kadettecu.modelo.EstadoConexao;

/**
 * Recebe avisos da conexão. Os métodos são chamados nas threads da conexão, não na thread
 * do Swing: quem atualiza tela deve usar SwingUtilities.invokeLater.
 */
public interface OuvinteConexao {

    void aoMudarEstado(EstadoConexao estado);

    void aoRegistrar(String mensagem);
}
