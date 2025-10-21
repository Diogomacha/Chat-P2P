package br.ufsm.poli.csi.redes.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Mensagem {

    public enum TipoMensagem {
        sonda,
        msg_individual,
        msg_grupo,
        fim_chat,
        disponivel
    }

    private TipoMensagem tipoMensagem;
    private String usuario;
    private String status;
    private String msg;
}